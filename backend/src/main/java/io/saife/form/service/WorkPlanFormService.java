package io.saife.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.core.domain.Equipment;
import io.saife.core.domain.RiskLevel;
import io.saife.core.domain.Site;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.ProcessRepository;
import io.saife.core.repository.SiteRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.evidence.Evidence;
import io.saife.evidence.domain.WorkPlanEvidence;
import io.saife.evidence.repository.WorkPlanEvidenceRepository;
import io.saife.form.dto.WorkPlanFormView;
import io.saife.workplan.domain.WorkDocument;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.dto.WorkPlanDtos;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import io.saife.workplan.repository.WorkPlanWorkerRepository;
import io.saife.workplan.service.BriefingViewBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「작업 전 안전점검표 (TBM)」 서식 값.
 *
 * <p>근거: 안전보건규칙 제42조(추락의 방지), 법 제36조제4항(위험성평가 결과의 근로자 공유,
 * 작업 전 안전점검회의), 고시 제13조. 사다리 도장은 제38조 작업계획서 대상이 아니므로 작업계획서로
 * 부르지 않는다. 제38조 대상 작업이면 같은 서식이 「작업계획서」로 제목과 근거만 바뀐다.
 *
 * <p>화면 결과 카드와 같은 판정({@link BriefingViewBuilder})을 그대로 찍는다. 화면과 출력물이
 * 다른 값을 말하면 그 순간 신뢰를 잃는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkPlanFormService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 서식의 모든 시각은 한국 시간이다. timestamptz가 UTC로 돌아와 9시간 틀리게 찍힌 적이 있다 */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final WorkPlanRepository workPlanRepository;
    private final WorkPlanSlotRepository workPlanSlotRepository;
    private final WorkPlanWorkerRepository workPlanWorkerRepository;
    private final WorkPlanEvidenceRepository workPlanEvidenceRepository;
    private final SiteRepository siteRepository;
    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;
    private final BriefingViewBuilder briefingViewBuilder;
    private final io.saife.workplan.service.WorkPlanService workPlanService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WorkPlanFormView build(Long workPlanId) {
        WorkPlan plan = workPlanRepository.findById(workPlanId).orElseThrow(
                () -> new NotFoundException("점검 기록을 찾을 수 없습니다."));
        Site site = siteRepository.findById(plan.getSiteId()).orElse(null);
        Equipment equipment = plan.getEquipmentId() == null ? null
                : equipmentRepository.findById(plan.getEquipmentId()).orElse(null);
        WorkDocument document = WorkDocument.of(plan.getWorkName(), equipment == null ? null : equipment.getName());

        AtomicInteger no = new AtomicInteger(1);
        List<WorkPlanFormView.Worker> workers = workPlanWorkerRepository.findByWorkPlanId(workPlanId).stream()
                .map(w -> new WorkPlanFormView.Worker(no.getAndIncrement(), w.getName(), nvl(w.getPosition(), "")))
                .toList();

        Map<String, String> labels = RiskRuleEngine.SlotKeys.labels();
        List<String> order = new ArrayList<>(labels.keySet());
        List<WorkPlanFormView.Check> checks = workPlanSlotRepository.findByWorkPlanId(workPlanId).stream()
                .sorted(Comparator.comparingInt(s -> order.indexOf(s.getSlotKey()) < 0 ? 99 : order.indexOf(s.getSlotKey())))
                .map(s -> new WorkPlanFormView.Check(
                        labels.getOrDefault(s.getSlotKey(), s.getSlotKey()),
                        nvl(RiskRuleEngine.SlotKeys.displayValue(s.getSlotKey(), s.getAnsweredValue()), "-"),
                        nvl(s.getLedgerValue(), "-"),
                        s.isConflicted()))
                .toList();

        WorkPlanDtos.BriefingView view = briefingViewBuilder.compute(plan);
        List<WorkPlanFormView.Decision> decisions = view.decisions().stream()
                .map(d -> new WorkPlanFormView.Decision(d.label(), d.riskLevel().getLabel(), gradeClass(d.riskLevel()),
                        d.ruleTrace(), nvl(d.recommendation(), "")))
                .toList();
        // 출력 시점에 따라 바뀌는 경과일은 쓰지 않는다. 기한 날짜로 적는다
        List<String> pending = view.pendingActions().stream()
                .map(a -> a.content() + (a.dueDate() == null ? "" : " (기한 " + a.dueDate() + ")"))
                .toList();

        List<WorkPlanFormView.Reference> references = workPlanEvidenceRepository
                .findByWorkPlanIdOrderByEvidenceNo(workPlanId).stream()
                .map(this::toReference)
                .filter(Objects::nonNull)
                .toList();
        // 번호는 서식 안에서 1부터 다시 매긴다. 대화 원장 번호(#n)는 서식 독자에게 의미가 없다
        AtomicInteger refNo = new AtomicInteger(1);
        references = references.stream()
                .map(r -> new WorkPlanFormView.Reference(refNo.getAndIncrement(), r.title(), r.source(), r.sourceUrl()))
                .toList();

        boolean workPlanDoc = io.saife.workplan.domain.WorkDocument.WORK_PLAN.equals(document.type());
        String supervisor = workPlanService.detail(workPlanId).supervisor();
        List<String> preSurvey = view.preSurvey() == null ? List.of() : view.preSurvey();

        return new WorkPlanFormView(
                document.title(),
                document.basis(),
                document.type(),
                nvl(supervisor, ""),
                workPlanDoc ? preSurvey : List.of(),
                site == null ? "-" : site.getName(),
                equipmentLabel(equipment),
                plan.getWorkName(),
                nvl(plan.getWorkPlace(), "-"),
                plan.getWorkDate(),
                plan.getWorkHours() == null ? "-" : plan.getWorkHours().stripTrailingZeros().toPlainString() + "시간",
                nvl(plan.getMethod(), "-"),
                statusLabel(plan.getStatus()),
                nvl(plan.getApprovedBy(), ""),
                plan.getApprovedAt() == null ? "" : format(plan.getApprovedAt()),
                plan.getStatus() == WorkPlanStatus.HOLD ? "보류 전 승인" : "확인 시각",
                nvl(plan.getApprovalNote(), "-"),
                plan.getWarningNote(),
                workers, checks, decisions,
                view.riskPoints(), view.keepPoints(),
                msdsLine(view.msds()),
                pending,
                "위험하면 작업을 멈추고 관리감독자에게 알립니다.",
                plan.getBriefingAckAt() == null ? "" : format(plan.getBriefingAckAt()),
                references);
    }

    private String msdsLine(WorkPlanDtos.MsdsSummary msds) {
        if (msds == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(msds.productName());
        if (msds.inferred()) {
            sb.append(", 추정 주성분 ").append(msds.chemName()).append(", 제품 MSDS 확인 필요");
        }
        for (WorkPlanDtos.MsdsLine l : msds.lines()) {
            if ("유해성".equals(l.item()) || "보호구".equals(l.item())) {
                sb.append(" / ").append(l.item()).append(' ').append(l.text());
            }
        }
        return sb.toString();
    }

    /** 참고 자료는 출처 이름과 조문(제목)으로 적는다. 인쇄물에 링크 글자나 긴 URL을 찍지 않는다 */
    private WorkPlanFormView.Reference toReference(WorkPlanEvidence row) {
        try {
            Evidence e = objectMapper.readValue(row.getPayload(), Evidence.class);
            String source = switch (e.kind()) {
                case LAW -> "법제처";
                case GUIDE -> "KOSHA GUIDE";
                case MSDS -> "MSDS";
                case CASE_FATALITY, CASE_DISASTER -> "한국산업안전보건공단 재해사례";
            };
            return new WorkPlanFormView.Reference(e.no(), cleanTitle(e), source, e.sourceUrl());
        } catch (Exception ex) {
            log.warn("[FORM] 근거 복원 실패 workPlanId={} no={}: {}", row.getWorkPlanId(), row.getEvidenceNo(), ex.getMessage());
            return null;
        }
    }

    /**
     * 참고 자료 제목을 서식 표기로: 조문은 "법령명 제n조(제목)"까지만, 사례는 원문 분류 꼬리표([추락] 등)를 떼고,
     * 가운뎃점과 대시는 쉼표로 바꾼다.
     */
    static String cleanTitle(Evidence e) {
        String t = e.title() == null ? "" : e.title();
        if (e.kind() == io.saife.evidence.EvidenceKind.LAW && t.contains(": ")) {
            t = t.substring(0, t.indexOf(": "));
        }
        if (e.kind() != null && e.kind().isCase()) {
            t = t.replaceFirst("^(\\s*\\[[^\\]]*\\]\\s*)+", "").replaceFirst("\\s*\\(\\d{4,8}\\)\\s*$", "");
            t = t.replace("작업중", "작업 중").replace("알콜", "알코올");
        }
        return t.replace(" — ", ", ").replace("—", ", ").replace("–", ", ")
                .replace("ㆍ", ", ").replace("·", ", ").replaceAll("\\s{2,}", " ").trim();
    }

    private String equipmentLabel(Equipment e) {
        if (e == null) {
            return "-";
        }
        String loc = e.getProcessId() == null ? e.getLocationTag()
                : processRepository.findById(e.getProcessId()).map(p -> p.getLocationTag()).orElse(e.getLocationTag());
        return loc == null ? e.getName() : "%s (%s)".formatted(e.getName(), loc);
    }

    private static String gradeClass(RiskLevel level) {
        return switch (level) {
            case HIGH -> "risk-high";
            case MEDIUM -> "risk-medium";
            case LOW -> "risk-low";
        };
    }

    private static String statusLabel(WorkPlanStatus status) {
        return switch (status) {
            case SUBMITTED -> "승인 대기";
            case APPROVED -> "승인";
            case CONDITIONAL -> "조건부 승인";
            case HOLD -> "작업 보류";
            case CLOSED -> "완료";
            default -> "작성 중";
        };
    }

    private static String format(OffsetDateTime ts) {
        return ts.atZoneSameInstant(KST).format(TS);
    }

    private static String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
