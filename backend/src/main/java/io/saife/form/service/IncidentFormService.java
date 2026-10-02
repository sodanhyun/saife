package io.saife.form.service;

import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.evidence.Evidence;
import io.saife.form.dto.IncidentFormViews;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.repository.IncidentRepository;
import io.saife.incident.service.EquipmentHistoryRecaller;
import io.saife.incident.service.FollowUpAssessmentService;
import io.saife.incident.service.IncidentEvidenceCollector;
import io.saife.incident.service.IncidentService;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사고 서식 두 종의 값을 모은다: 제출용 산업재해조사표, 사내 재발방지 검토서.
 *
 * <p>둘을 나눈 이유: 조사표는 관할 관서에 제출하는 문서라 사실만 적고, 사고 전 지적 사항의
 * 미이행 경위처럼 사업장 내부 판단이 필요한 내용은 사내 검토서에 둔다(제출하지 않음).
 *
 * <p>서식의 모든 시각은 한국 시간으로 찍는다({@code FormDataService}와 같은 이유).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentFormService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** "[#3]" 인용 */
    private static final Pattern CITATION = Pattern.compile("\\s*\\[#(\\d+)]");
    /** "1. 무엇을 (담당 누가, 기한 언제까지)" */
    private static final Pattern PLAN_LINE = Pattern.compile(
            "^\\s*(?:\\d+[.)]\\s*)?(.+?)\\s*\\(\\s*담당\\s*([^,()]+?)\\s*,\\s*기한\\s*([^()]+?)\\s*\\)\\s*\\.?\\s*$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s*");

    private static final Map<String, String> INDUSTRY = Map.of(
            "C25", "금속 가공제품 제조업 (기계 및 가구 제외)",
            "C29", "기타 기계 및 장비 제조업");

    private final IncidentRepository incidentRepository;
    private final SiteRepository siteRepository;
    private final EquipmentRepository equipmentRepository;
    private final ActionRepository actionRepository;
    private final WorkPlanRepository workPlanRepository;
    private final EquipmentHistoryRecaller recaller;
    private final FollowUpAssessmentService followUpAssessmentService;
    private final AssessmentRepository assessmentRepository;
    private final IncidentEvidenceCollector evidenceCollector;

    // ────────────────────────── 산업재해조사표 (제출용) ──────────────────────────

    @Transactional(readOnly = true)
    public IncidentFormViews.ReportForm reportForm(Long incidentId) {
        Incident incident = find(incidentId);
        Site site = siteRepository.findById(incident.getSiteId()).orElse(null);
        EquipmentHistoryRecaller.Recall recall = recall(incident);
        ZonedDateTime at = incident.getOccurredAt().atZoneSameInstant(KST);

        String workType = incident.getWorkPlanId() == null ? ""
                : workPlanRepository.findById(incident.getWorkPlanId()).map(WorkPlan::getWorkName).orElse("");

        List<IncidentFormViews.PlanRow> plans = planRows(incident.getPrevention()).stream()
                .map(p -> new IncidentFormViews.PlanRow(p.no(), p.what(), p.who(), p.when(), List.of()))
                .toList();

        return new IncidentFormViews.ReportForm(
                site == null ? "" : site.getName(),
                site == null || site.getWorkerCount() == null ? "" : site.getWorkerCount() + "명",
                site == null ? "" : industry(site.getIndustryCode()),
                site == null ? "" : nvl(site.getAddress(), ""),
                incident.getLeaveDays() == null ? "" : String.valueOf(incident.getLeaveDays()),
                incident.getSeverity() == IncidentSeverity.FATALITY,
                "%s (%s)".formatted(at.toLocalDate(), at.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN)),
                at.format(HM),
                place(recall),
                workType,
                nvl(incident.getDescription(), ""),
                stripCitations(nvl(incident.getCause(), "")),
                plans,
                dueLabel(incident));
    }

    // ────────────────────────── 재발방지 검토서 (사내) ──────────────────────────

    @Transactional(readOnly = true)
    public IncidentFormViews.ReviewForm reviewForm(Long incidentId) {
        Incident incident = find(incidentId);
        Site site = siteRepository.findById(incident.getSiteId()).orElse(null);
        EquipmentHistoryRecaller.Recall recall = recall(incident);
        LocalDate occurredOn = incident.getOccurredAt().atZoneSameInstant(KST).toLocalDate();

        // 근거 각주: 등록 응답과 같은 수집기로 다시 모은다. 실패하면 각주 없이 번호만 지운다
        Map<Integer, Evidence> evidenceByNo = new HashMap<>();
        try {
            String query = (nvl(recall.equipmentName(), "") + " " + nvl(incident.getDescription(), "")).strip();
            for (Evidence e : evidenceCollector.collect(query, incident.getAccidentType()).all()) {
                evidenceByNo.put(e.no(), e);
            }
        } catch (Exception e) {
            log.warn("[FORM] 검토서 근거 수집 실패 incidentId={}: {}", incidentId, e.getMessage());
        }

        // 각주는 본문에 나온 순서대로 1부터 다시 매긴다(화면의 근거 번호와 서식의 각주 번호는 따로 간다)
        List<IncidentFormViews.PlanRow> plans = new ArrayList<>();
        Map<Integer, Integer> footnoteNo = new LinkedHashMap<>();
        for (IncidentFormViews.PlanRow p : planRows(incident.getPrevention())) {
            List<Integer> refs = p.refs().stream()
                    .filter(evidenceByNo::containsKey)
                    .map(n -> footnoteNo.computeIfAbsent(n, k -> footnoteNo.size() + 1))
                    .distinct()
                    .toList();
            plans.add(new IncidentFormViews.PlanRow(p.no(), p.what(), p.who(), p.when(), refs));
        }
        List<IncidentFormViews.FootnoteRow> footnotes = footnoteNo.entrySet().stream()
                .map(e -> new IncidentFormViews.FootnoteRow(e.getValue(),
                        evidenceByNo.get(e.getKey()).title(), evidenceByNo.get(e.getKey()).sourceUrl()))
                .toList();

        FollowUpAssessmentService.Result followUp =
                followUpAssessmentService.reconstruct(incident.getFollowUpAssessmentId());
        List<IncidentFormViews.RegradeRow> regrades = followUp.regraded().stream()
                .map(g -> new IncidentFormViews.RegradeRow(
                        g.accidentType() == null ? "" : g.accidentType().getLabel(),
                        nvl(g.missingControl(), ""),
                        g.before() == null ? "신규" : g.before().getLabel(),
                        g.after().getLabel(), riskClass(g.after()), nvl(g.ruleTrace(), "")))
                .toList();

        List<IncidentFormViews.HoldRow> holds = incident.getEquipmentId() == null ? List.of()
                : workPlanRepository.findBySiteIdAndEquipmentIdAndStatusInAndWorkDateGreaterThanEqualOrderByWorkDateAsc(
                                incident.getSiteId(), incident.getEquipmentId(),
                                List.of(WorkPlanStatus.HOLD), occurredOn).stream()
                        .filter(p -> p.getWarningNote() != null && p.getWarningNote().contains(IncidentService.HOLD_WARNING))
                        .map(p -> new IncidentFormViews.HoldRow(p.getWorkDate().toString(), p.getWorkName(), "작업 보류"))
                        .toList();

        String leave = incident.getSeverity() == IncidentSeverity.FATALITY ? "사망"
                : incident.getLeaveDays() == null ? "미입력" : incident.getLeaveDays() + "일";

        return new IncidentFormViews.ReviewForm(
                site == null ? "" : site.getName(),
                recall.equipmentName(),
                nvl(recall.locationTag(), ""),
                format(incident.getOccurredAt()),
                incident.getAccidentType() == null ? "" : incident.getAccidentType().getLabel(),
                incident.getSeverity() == null ? "" : incident.getSeverity().getLabel(),
                leave,
                nvl(incident.getDescription(), ""),
                dueLabel(incident),
                priorRows(recall, occurredOn),
                plans,
                followUpLabel(incident.getFollowUpAssessmentId()),
                regrades,
                holds,
                footnotes);
    }

    /**
     * 사고 전 지적 사항: 사고 전 위험요인마다 그 감소대책과 사고 시점 이행 상태.
     * 사고와 같은 발생형태가 맨 위다(소환 순서 그대로).
     */
    private List<IncidentFormViews.PriorRow> priorRows(EquipmentHistoryRecaller.Recall recall, LocalDate occurredOn) {
        List<IncidentFormViews.PriorRow> rows = new ArrayList<>();
        for (EquipmentHistoryRecaller.PriorHazard h : recall.priorHazards()) {
            Action action = actionRepository.findByHazardId(h.hazardId()).stream()
                    .filter(a -> a.getCreatedAt() == null || !a.getCreatedAt().atZoneSameInstant(KST).toLocalDate().isAfter(occurredOn))
                    .max(Comparator.comparing(Action::getId))
                    .orElse(null);

            String status = "";
            String elapsed = "";
            boolean overdue = false;
            if (action != null) {
                LocalDate completed = action.getCompletedAt() == null ? null
                        : action.getCompletedAt().atZoneSameInstant(KST).toLocalDate();
                if (completed != null && !completed.isAfter(occurredOn)) {
                    status = "이행 완료";
                    elapsed = completed.toString();
                } else if (action.getDueDate() != null && occurredOn.isAfter(action.getDueDate())) {
                    status = "미이행";
                    elapsed = "%d일 경과".formatted(ChronoUnit.DAYS.between(action.getDueDate(), occurredOn));
                    overdue = true;
                } else {
                    status = "미이행";
                    elapsed = "기한 전";
                }
            }
            rows.add(new IncidentFormViews.PriorRow(
                    h.lastAssessedOn() == null ? "" : h.lastAssessedOn().toString(),
                    h.accidentType() == null ? "" : h.accidentType().getLabel(),
                    nvl(h.missingControl(), nvl(h.description(), "")),
                    h.lastRiskLevel() == null ? "미평가" : h.lastRiskLevel().getLabel(),
                    riskClass(h.lastRiskLevel()),
                    action == null ? "(등록된 감소대책 없음)" : action.getContent(),
                    action == null ? "" : nvl(action.getOwner(), ""),
                    action == null || action.getDueDate() == null ? "" : action.getDueDate().toString(),
                    status, elapsed, overdue, h.sameAxisAsIncident()));
        }
        return rows;
    }

    private String followUpLabel(Long assessmentId) {
        if (assessmentId == null) {
            return "생성되지 않음";
        }
        return assessmentRepository.findById(assessmentId)
                .map(a -> "%s 수시평가, %s".formatted(a.getAssessedOn(),
                        "CONFIRMED".equals(a.getStatus()) ? "확정" : "작성 중 (작업 재개 전 완료)"))
                .orElse("생성되지 않음");
    }

    // ────────────────────────── 공통 ──────────────────────────

    /**
     * 재발방지 문안을 줄로 가른다. 근거 번호는 {@code refs}로 뽑고 본문에서는 지운다.
     * 줄머리 번호는 다시 매긴다(모델이 빠뜨리거나 건너뛰어도 1, 2, 3).
     */
    static List<IncidentFormViews.PlanRow> planRows(String prevention) {
        List<IncidentFormViews.PlanRow> out = new ArrayList<>();
        if (prevention == null || prevention.isBlank()) {
            return out;
        }
        int no = 1;
        for (String raw : prevention.split("\\r?\\n")) {
            if (raw.isBlank()) {
                continue;
            }
            List<Integer> refs = new ArrayList<>();
            Matcher cm = CITATION.matcher(raw);
            while (cm.find()) {
                refs.add(Integer.parseInt(cm.group(1)));
            }
            String line = CITATION.matcher(raw).replaceAll("").strip();
            Matcher m = PLAN_LINE.matcher(line);
            if (m.matches()) {
                out.add(new IncidentFormViews.PlanRow(no++, m.group(1).strip(), m.group(2).strip(), m.group(3).strip(), refs));
            } else {
                out.add(new IncidentFormViews.PlanRow(no++, NUMBERED.matcher(line).replaceFirst("").strip(), "", "", refs));
            }
        }
        return out;
    }

    static String stripCitations(String text) {
        return text == null ? "" : CITATION.matcher(text).replaceAll("");
    }

    private String dueLabel(Incident incident) {
        if (incident.getReportStatus() == ReportStatus.SUBMITTED) {
            return "제출 완료";
        }
        if (incident.getReportDueDate() != null) {
            return "제출 기한 %s (발생일부터 1개월), 관할 지방고용노동관서".formatted(incident.getReportDueDate());
        }
        return IncidentService.reportBasis(incident);
    }

    private String place(EquipmentHistoryRecaller.Recall recall) {
        String loc = recall.locationTag();
        String name = recall.equipmentName();
        if (loc == null || loc.isBlank()) {
            return nvl(name, "");
        }
        return "%s (%s)".formatted(loc, name);
    }

    private EquipmentHistoryRecaller.Recall recall(Incident incident) {
        return recaller.recall(incident.getEquipmentId(), incident.getAccidentType(),
                incident.getOccurredAt(), incident.getCreatedAt(),
                EquipmentHistoryRecaller.Purpose.POST_INCIDENT);
    }

    private Incident find(Long incidentId) {
        return incidentRepository.findById(incidentId).orElseThrow(
                () -> new NotFoundException("사고를 찾을 수 없습니다: " + incidentId));
    }

    private String industry(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return INDUSTRY.getOrDefault(code, code);
    }

    private String riskClass(RiskLevel level) {
        if (level == null) {
            return "";
        }
        return switch (level) {
            case HIGH -> "risk-high";
            case MEDIUM -> "risk-medium";
            case LOW -> "risk-low";
        };
    }

    private String format(OffsetDateTime ts) {
        return ts == null ? "" : ts.atZoneSameInstant(KST).format(TS);
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
