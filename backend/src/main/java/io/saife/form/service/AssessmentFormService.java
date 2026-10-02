package io.saife.form.service;

import io.saife.core.action.ControlPriority;
import io.saife.core.action.InspectionRecordStore;
import io.saife.core.action.InspectionRules;
import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.core.domain.*;
import io.saife.core.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 위험성평가표. 시행규칙 제37조의4(기록) 항목을 그 순서대로 채운다.
 *
 * <ol>
 *   <li>실시 시기와 담당자</li>
 *   <li>참여한 근로자 및 근로자대표</li>
 *   <li>유해·위험요인, 위험성 수준 결정 결과(3단계 판단법, 허용 가능 여부), 개선대책 수립 내용 및 이행 결과</li>
 * </ol>
 *
 * <p>상태(작성 중/확정)는 화면과 같은 규칙({@link InspectionRules#complete})으로 낸다.
 * 빈도와 강도 숫자는 서식에 쓰지 않는다. 등급은 3단계(상/중/하)로만 적는다.
 */
@Service
@RequiredArgsConstructor
public class AssessmentFormService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static final String BASIS = "산업안전보건법 제36조, 같은 법 시행규칙 제37조의4 (기록 및 보존, 3년)";
    static final String METHOD = "3단계 판단법 (고시 제7조)";

    private final SiteRepository siteRepository;
    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;
    private final HazardRepository hazardRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final ActionRepository actionRepository;
    private final InspectionRecordStore records;

    /**
     * @param participants 참여 근로자. 서식에서 이름마다 서명란을 둔다
     * @param workerRep    근로자대표. 기록이 없으면 빈 작성란
     * @param complete     확정이면 true
     */
    public record Form(String siteName, String kindLabel, LocalDate assessedOn, String inspector,
                       List<String> participants, String workerRep, String method,
                       String statusLabel, boolean complete, List<Row> rows, String basis) {}

    /**
     * 위험요인 한 줄.
     *
     * @param work          공정/작업 (공정명, 설비명)
     * @param hazard        유해위험요인 (발생형태: 빠진 조치)
     * @param hazardDetail  현장 서술. 사진 판독 내용이나 대장 서술
     * @param currentMeasure 현재 안전조치. 이 점검 전에 이행이 끝난 조치
     * @param riskLabel     상/중/하
     * @param riskBasis     등급 근거 (짧은 평문)
     * @param acceptable    허용 가능 여부 "가능"/"불가"
     * @param improvement   개선대책. 허용 가능이면 "현 상태 유지"
     * @param priority      개선대책 우선순위 라벨(제거, 공학적, 관리적, 보호구). 없으면 null
     * @param result        이행 결과. "완료 2026-10-02", "이행 예정", "기한 경과"
     */
    public record Row(int no, String work, String hazard, String hazardDetail, String currentMeasure,
                      String riskLabel, String riskClass, String riskBasis, String acceptable,
                      String improvement, String priority, String owner, String dueDate, String result,
                      String guideRef) {}

    @Transactional(readOnly = true)
    public Form form(Long assessmentId) {
        Assessment assessment = assessmentRepository.findById(assessmentId).orElseThrow(
                () -> new NotFoundException("평가를 찾을 수 없습니다: " + assessmentId));
        Site site = siteRepository.findById(assessment.getSiteId()).orElse(null);
        InspectionRecordStore.Inspection inspection = records.inspection(assessmentId).orElse(null);
        String participantsRaw = inspection == null ? assessment.getParticipants() : inspection.participants();
        Map<Long, Boolean> acceptableStored = records.acceptableByHazard(assessmentId);

        List<Row> rows = new ArrayList<>();
        List<InspectionRules.Item> items = new ArrayList<>();
        int no = 1;
        for (AssessmentHazard link : assessmentHazardRepository.findByAssessmentId(assessmentId)) {
            Hazard hazard = hazardRepository.findById(link.getHazardId()).orElse(null);
            if (hazard == null) {
                continue;
            }
            boolean acceptable = InspectionRules.acceptable(acceptableStored.get(hazard.getId()), link.getRiskLevel());
            List<Action> actions = actionRepository.findByHazardId(hazard.getId());
            Action action = improvementFor(actions, assessmentId).orElse(null);
            boolean planned = action != null && (assessmentId.equals(action.getAssessmentId())
                    || !InspectionRules.overdue(action.getDueDate()));
            items.add(new InspectionRules.Item(hazard.isAiSuggested(), hazard.getAiAdopted(), acceptable, planned));

            // 제외한 위험요인은 이 평가의 유해위험요인이 아니다. 서식에 올리지 않는다
            if (hazard.isAiSuggested() && Boolean.FALSE.equals(hazard.getAiAdopted())) {
                continue;
            }
            ControlPriority priority = action == null ? null : records.priority(action.getId());
            String risk = riskLabel(link.getRiskLevel());
            rows.add(new Row(no++,
                    work(hazard),
                    label(hazard) + ": " + nvl(hazard.getMissingControl(), "-"),
                    detail(hazard),
                    currentMeasure(actions, assessmentId, assessment.getAssessedOn()),
                    risk,
                    switch (risk) { case "상" -> "risk-high"; case "중" -> "risk-medium"; default -> "risk-low"; },
                    nvl(link.getRuleTrace(), "-"),
                    acceptable ? "가능" : "불가",
                    acceptable && action == null ? "현 상태 유지" : action == null ? "(미수립)" : action.getContent(),
                    priority == null ? null : priority.getLabel(),
                    action == null ? null : action.getOwner(),
                    action == null || action.getDueDate() == null ? null : action.getDueDate().toString(),
                    action == null ? (acceptable ? "-" : "미수립") : result(action),
                    action == null ? null : action.getGuideRef()));
        }

        boolean complete = "CONFIRMED".equals(assessment.getStatus())
                || InspectionRules.complete(participantsRaw, items);
        return new Form(
                site == null ? "-" : site.getName(),
                kindLabel(assessment),
                assessment.getAssessedOn(),
                inspection == null || inspection.inspector() == null ? "" : inspection.inspector(),
                InspectionRules.splitParticipants(participantsRaw),
                "",
                METHOD,
                complete ? "확정" : "작성 중",
                complete,
                rows,
                BASIS);
    }

    /** 이 점검에서 수립한 개선대책. 없으면 끝나지 않은 기존 조치(그 이행이 곧 개선대책이다) */
    private Optional<Action> improvementFor(List<Action> actions, Long assessmentId) {
        return actions.stream()
                .filter(a -> assessmentId.equals(a.getAssessmentId()))
                .max(Comparator.comparing(Action::getId))
                .or(() -> actions.stream()
                        .filter(a -> a.getStatus() != ActionStatus.DONE)
                        .min(Comparator.comparing(Action::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))));
    }

    /** 현재 안전조치: 다른 평가에서 수립해 이 점검일까지 이행을 마친 조치 */
    private String currentMeasure(List<Action> actions, Long assessmentId, LocalDate assessedOn) {
        List<String> done = actions.stream()
                .filter(a -> !assessmentId.equals(a.getAssessmentId()))
                .filter(a -> a.getStatus() == ActionStatus.DONE && a.getCompletedAt() != null)
                .filter(a -> assessedOn == null
                        || !a.getCompletedAt().atZoneSameInstant(KST).toLocalDate().isAfter(assessedOn))
                .map(Action::getContent)
                .toList();
        return done.isEmpty() ? "없음" : String.join(", ", done);
    }

    private String result(Action action) {
        if (action.getStatus() == ActionStatus.DONE && action.getCompletedAt() != null) {
            return "완료 " + action.getCompletedAt().atZoneSameInstant(KST).toLocalDate();
        }
        LocalDate today = LocalDate.now(KST);
        if (action.getDueDate() != null && action.getDueDate().isBefore(today)) {
            return "기한 경과";
        }
        return "이행 예정";
    }

    private String work(Hazard hazard) {
        String process = hazard.getProcessId() == null ? null
                : processRepository.findById(hazard.getProcessId()).map(WorkProcess::getName).orElse(null);
        String equipment = hazard.getEquipmentId() == null ? null
                : equipmentRepository.findById(hazard.getEquipmentId())
                        .map(e -> {
                            if (process == null && e.getProcessId() != null) {
                                return processRepository.findById(e.getProcessId())
                                        .map(p -> p.getName() + " / " + e.getName()).orElse(e.getName());
                            }
                            return e.getName();
                        })
                        .orElse(null);
        if (process != null && equipment != null) {
            return process + " / " + equipment;
        }
        return equipment != null ? equipment : process != null ? process : "-";
    }

    private String detail(Hazard hazard) {
        String d = hazard.getDescription();
        if (d == null || d.isBlank() || d.equals(hazard.getMissingControl())) {
            return null;
        }
        return d.replace("·", ", ");
    }

    private String label(Hazard hazard) {
        return hazard.getAccidentType() == null ? "-" : hazard.getAccidentType().getLabel();
    }

    private String kindLabel(Assessment a) {
        String kind = a.getKind() == null ? "-" : a.getKind().getLabel() + "평가";
        String trigger = switch (a.getTriggerType() == null ? "" : a.getTriggerType()) {
            case "PHOTO", "PATROL" -> "순회점검";
            case "INCIDENT" -> "산업재해 발생";
            case "EQUIPMENT_CHANGE" -> "설비 변경";
            case "SCHEDULE" -> "정기 일정";
            default -> null;
        };
        return trigger == null ? kind : kind + " (" + trigger + ")";
    }

    private String riskLabel(RiskLevel level) {
        if (level == null) {
            return "-";
        }
        return switch (level) {
            case HIGH -> "상";
            case MEDIUM -> "중";
            case LOW -> "하";
        };
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
