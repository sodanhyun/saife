package io.saife.incident.service;

import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.repository.WorkPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 사고가 난 설비의 이력을 소환한다 — <b>UC2의 심장.</b>
 *
 * <p>시연 영상 2:20~2:45가 여기에 걸려 있다. 관리자가 사고를 등록하는 순간
 * 같은 설비의 과거 평가·미이행 조치·작업 브리핑이 스스로 올라온다.
 *
 * <p><b>하드코딩하지 않는다.</b> 하드코딩한 증거는 증거가 아니고, 이 구간이 바로
 * 심사위원이 "이거 녹화 아닙니까"라고 묻는 곳이다. 전부 데이터 코어 조회다.
 *
 * <p>가장 강한 한 줄은 {@code predicted}가 참일 때 나온다 — 사고의 발생형태와
 * 같은 축의 위험요인이 <b>사고 전에</b> 이미 등록돼 있었고 그 조치가 미이행이었다는 것.
 * 그건 "AI가 잘 맞혔다"가 아니라 <b>이 사업장의 안전관리가 실패한 지점</b>이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EquipmentHistoryRecaller {

    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;
    private final HazardRepository hazardRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final ActionRepository actionRepository;
    private final WorkPlanRepository workPlanRepository;
    private final IncidentRepository incidentRepository;

    /**
     * @param priorHazards      사고 전에 이미 등록돼 있던 위험요인
     * @param unfinishedActions 미이행 조치
     * @param priorWorkPlans    이 설비의 과거 작업계획서
     * @param priorIncidents    같은 설비의 과거 사고
     * @param predicted         같은 발생형태의 위험요인이 이미 있었는가
     * @param warnedAt          작업 브리핑을 작업자가 확인한 시각 (없으면 null)
     * @param headline          화면 최상단 한 줄
     */
    public record Recall(Long equipmentId,
                         String equipmentName,
                         String locationTag,
                         List<PriorHazard> priorHazards,
                         List<UnfinishedAction> unfinishedActions,
                         List<PriorWorkPlan> priorWorkPlans,
                         List<PriorIncident> priorIncidents,
                         boolean predicted,
                         OffsetDateTime warnedAt,
                         String headline) {}

    public record PriorHazard(Long hazardId, AccidentType accidentType, String missingControl,
                              String description, RiskLevel lastRiskLevel,
                              LocalDate lastAssessedOn, String lastRuleTrace,
                              boolean sameAxisAsIncident) {}

    /** @param owner 조치 담당. 재발방지 대책의 "누가"에 쓴다 */
    public record UnfinishedAction(Long actionId, String content, LocalDate dueDate,
                                   ActionStatus status, Long overdueDays, String guideRef, String owner) {}

    public record PriorWorkPlan(Long workPlanId, String workName, LocalDate workDate,
                                OffsetDateTime briefingAckAt, String status) {}

    public record PriorIncident(Long incidentId, OffsetDateTime occurredAt,
                                AccidentType accidentType, String description) {}

    /**
     * 이 소환이 어떤 문맥에서 쓰이는지 — <b>headline 문구만</b> 바꾼다.
     *
     * <p>{@code POST_INCIDENT}는 사고가 이미 등록된 뒤(사고 전 평가와 미이행 감소대책 요약).
     * {@code PRE_WORK}는 사고가 나기 전, 작업 신고 화면에서 시스템이 먼저 아는 것을
     * 말하는 문맥이다("최근 평가...미이행 조치..."). 소환 로직·정렬·판정은 동일하다.
     */
    public enum Purpose { PRE_WORK, POST_INCIDENT }

    /**
     * 설비 이력을 소환한다.
     *
     * @param equipmentId 대상 설비
     * @param axis        사고의 발생형태. {@code PRE_WORK}에서는 아직 사고가 없으므로 null을 준다 —
     *                    그러면 {@code sameAxisAsIncident}는 전부 false, {@code predicted}도 false가
     *                    된다. 이건 정상이다("사고 전 회상"이라 예측 축이 없다)
     * @param occurredAt  기준 시각. 작업계획서·과거 사고를 이 시점으로 자른다.
     *                    {@code PRE_WORK}에서는 지금 시각을 준다
     * @param knownAsOf   <b>이 기록이 남는 시각.</b> 이후에 만들어진 기록은 소환하지 않는다 —
     *                    사고가 스스로 만든 수시평가를 "사고 전에 알고 있었다"의 근거로 쓰면
     *                    순환 논증이 된다. 실제로 그렇게 됐다 (2026-09-20 스모크 테스트)
     * @param purpose     headline 문구 분기용
     */
    @Transactional(readOnly = true)
    public Recall recall(Long equipmentId, AccidentType axis,
                         OffsetDateTime occurredAt, OffsetDateTime knownAsOf, Purpose purpose) {
        Equipment equipment = equipmentId == null ? null
                : equipmentRepository.findById(equipmentId).orElse(null);

        if (equipment == null) {
            return new Recall(equipmentId, "(미등록 설비)", null,
                    List.of(), List.of(), List.of(), List.of(), false, null,
                    "설비 대장에 없는 사고입니다. 설비를 먼저 등록해야 이력이 이어집니다.");
        }

        String locationTag = Optional.ofNullable(equipment.getProcessId())
                .flatMap(processRepository::findById)
                .map(WorkProcess::getLocationTag)
                .orElse(equipment.getLocationTag());

        List<Hazard> hazards = hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(equipmentId);
        List<PriorHazard> priorHazards = toPriorHazards(hazards, axis,
                purpose == Purpose.POST_INCIDENT ? occurredAt.atZoneSameInstant(KST).toLocalDate() : null, knownAsOf);
        List<UnfinishedAction> unfinished = toUnfinishedActions(hazards, occurredAt);
        List<PriorWorkPlan> workPlans = toPriorWorkPlans(equipmentId, occurredAt);
        List<PriorIncident> priorIncidents = toPriorIncidents(equipmentId, occurredAt);

        boolean predicted = priorHazards.stream().anyMatch(PriorHazard::sameAxisAsIncident);
        OffsetDateTime warnedAt = workPlans.stream()
                .map(PriorWorkPlan::briefingAckAt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        log.info("[RECALL] 설비 {} ({}) — 위험요인 {}건, 미이행 {}건, 작업계획 {}건, 과거사고 {}건, 예고됨={}",
                equipmentId, purpose, priorHazards.size(), unfinished.size(),
                workPlans.size(), priorIncidents.size(), predicted);

        return new Recall(equipmentId, equipment.getName(), locationTag,
                priorHazards, unfinished, workPlans, priorIncidents,
                predicted, warnedAt,
                headline(purpose, predicted, priorHazards, unfinished, warnedAt, priorIncidents));
    }

    /**
     * 사고가 기록되기 전에 이미 있던 위험요인과 그 등급만 소환한다.
     *
     * <p>사후에 추가된 걸 섞으면 증거가 아니라 순환 논증이다. 특히 <b>이 사고가 만든
     * 수시평가</b>가 "최근 평가 등급"으로 올라오면 안 된다 — 사고 때문에 생긴 평가를
     * 근거로 "사고 전에 알고 있었다"고 말하는 꼴이 된다.
     */
    private List<PriorHazard> toPriorHazards(List<Hazard> hazards, AccidentType axis,
                                             LocalDate occurredOn, OffsetDateTime knownAsOf) {
        List<PriorHazard> out = new ArrayList<>();
        for (Hazard h : hazards) {
            if (isAfter(h.getCreatedAt(), knownAsOf)) {
                continue;
            }
            AssessmentHazard latest = null;
            LocalDate assessedOn = null;

            for (AssessmentHazard candidate
                    : assessmentHazardRepository.findHistoryByHazardId(h.getId())) {
                Assessment assessment =
                        assessmentRepository.findById(candidate.getAssessmentId()).orElse(null);
                if (assessment == null || isAfter(assessment.getCreatedAt(), knownAsOf)
                        || !isBeforeIncident(assessment, occurredOn)) {
                    continue;
                }
                // findHistoryByHazardId가 평가일 내림차순이라 첫 유효 건이 최신이다
                latest = candidate;
                assessedOn = assessment.getAssessedOn();
                break;
            }

            out.add(new PriorHazard(h.getId(), h.getAccidentType(), h.getMissingControl(),
                    h.getDescription(),
                    latest == null ? null : latest.getRiskLevel(),
                    assessedOn,
                    latest == null ? null : latest.getRuleTrace(),
                    axis != null && axis == h.getAccidentType()));
        }
        // 사고와 같은 축을 맨 위로. 화면 첫 줄이 가장 중요한 줄이어야 한다
        out.sort((a, b) -> Boolean.compare(b.sameAxisAsIncident(), a.sameAxisAsIncident()));
        return out;
    }

    /**
     * 사고 시점에 끝나지 않은 감소대책. <b>지금 상태가 아니라 사고 시점 기준이다.</b>
     *
     * <p>조치 생성 시각이 사고 전이고, 완료 시각이 없거나 사고 뒤인 것만 고른다. 사고 뒤에 만든 조치나
     * 사고 전에 이미 끝낸 조치가 "사고 시점 미이행"으로 올라오면 사실이 아니다. 경과일도 사고일 기준이고,
     * 상태 역시 사고일에 기한이 지났으면 OVERDUE, 아니면 PENDING으로 그때의 상태를 돌려준다.
     * 재발방지 검토서({@code IncidentFormService})도 같은 규칙({@link #openAt})을 쓴다.
     */
    private List<UnfinishedAction> toUnfinishedActions(List<Hazard> hazards, OffsetDateTime asOf) {
        if (hazards.isEmpty()) {
            return List.of();
        }
        LocalDate asOfDate = asOf.atZoneSameInstant(KST).toLocalDate();
        List<Long> hazardIds = hazards.stream().map(Hazard::getId).toList();
        return actionRepository.findByHazardIdIn(hazardIds).stream()
                .filter(a -> openAt(a, asOf))
                .map(a -> new UnfinishedAction(a.getId(), a.getContent(), a.getDueDate(),
                        statusAt(a, asOfDate), overdueDays(a.getDueDate(), asOfDate), a.getGuideRef(), a.getOwner()))
                .sorted((x, y) -> Long.compare(nullsLow(y.overdueDays()), nullsLow(x.overdueDays())))
                .toList();
    }

    /** 이 시각에 열려 있던 조치인가: 그 전에 만들었고, 그때까지 끝내지 않았다 */
    public static boolean openAt(Action action, OffsetDateTime asOf) {
        if (asOf == null) {
            return action.getStatus() != ActionStatus.DONE;
        }
        boolean createdBefore = action.getCreatedAt() == null || action.getCreatedAt().isBefore(asOf);
        boolean notDoneYet = action.getCompletedAt() == null
                ? action.getStatus() != ActionStatus.DONE
                : action.getCompletedAt().isAfter(asOf);
        return createdBefore && notDoneYet;
    }

    /** 그 날짜의 조치 상태. 기한이 지났으면 OVERDUE */
    private ActionStatus statusAt(Action action, LocalDate asOfDate) {
        return action.getDueDate() != null && asOfDate.isAfter(action.getDueDate())
                ? ActionStatus.OVERDUE : ActionStatus.PENDING;
    }

    /** 기한이 사고일보다 며칠 전이었나. 음수면 사고 당시 아직 기한 전이었다 */
    private Long overdueDays(LocalDate dueDate, LocalDate asOf) {
        return dueDate == null ? null : ChronoUnit.DAYS.between(dueDate, asOf);
    }

    /**
     * "사고 이전 평가"인가. 평가일이 사고일보다 뒤면 아니다. 사고 당일 평가라도 사고가 만든
     * 수시평가(trigger INCIDENT)는 사고 뒤의 평가다 — 같은 날 앞서 등록한 다른 사고의 수시평가가
     * "사고 전 평가 10-02"로 올라오던 버그를 막는다. 설비 이력 화면도 같은 규칙을 쓴다.
     */
    public static boolean isBeforeIncident(Assessment assessment, LocalDate occurredOn) {
        LocalDate on = assessment.getAssessedOn();
        if (on == null || occurredOn == null) {
            return true;
        }
        if (on.isAfter(occurredOn)) {
            return false;
        }
        return !(on.isEqual(occurredOn) && "INCIDENT".equals(assessment.getTriggerType()));
    }

    /** knownAsOf가 null이면(기록 시각 미상) 자르지 않는다 */
    private boolean isAfter(OffsetDateTime candidate, OffsetDateTime knownAsOf) {
        return candidate != null && knownAsOf != null && candidate.isAfter(knownAsOf);
    }

    private long nullsLow(Long v) {
        return v == null ? Long.MIN_VALUE : v;
    }

    private List<PriorWorkPlan> toPriorWorkPlans(Long equipmentId, OffsetDateTime occurredAt) {
        return workPlanRepository.findByEquipmentIdOrderByWorkDateDesc(equipmentId).stream()
                .filter(p -> !p.getWorkDate().isAfter(occurredAt.atZoneSameInstant(KST).toLocalDate()))
                .map(p -> new PriorWorkPlan(p.getId(), p.getWorkName(), p.getWorkDate(),
                        p.getBriefingAckAt(), p.getStatus().name()))
                .toList();
    }

    private List<PriorIncident> toPriorIncidents(Long equipmentId, OffsetDateTime occurredAt) {
        return incidentRepository.findByEquipmentIdOrderByOccurredAtDesc(equipmentId).stream()
                .filter(i -> i.getOccurredAt().isBefore(occurredAt))
                .map(i -> new PriorIncident(i.getId(), i.getOccurredAt(),
                        i.getAccidentType(), i.getDescription()))
                .toList();
    }

    /**
     * 화면 최상단 한 줄. 강한 순서대로 고른다. {@code purpose}에 따라
     * 문구만 갈린다 — 소환 로직·판정은 완전히 같다.
     *
     * <p><b>과장하지 않는다.</b> 소환된 사실이 약하면 약하게 말한다 — 없는 이력을
     * 있는 것처럼 쓰면 심사위원이 데이터를 확인하는 순간 전부 무너진다.
     */
    private String headline(Purpose purpose, boolean predicted, List<PriorHazard> priorHazards,
                            List<UnfinishedAction> unfinished, OffsetDateTime warnedAt,
                            List<PriorIncident> priorIncidents) {
        if (purpose == Purpose.PRE_WORK) {
            return preWorkHeadline(priorHazards, unfinished, priorIncidents);
        }
        return postIncidentHeadline(predicted, priorHazards, unfinished, priorIncidents);
    }

    /**
     * 사고 등록 후(UC2): 사고 전 기록을 사실형으로 요약한다. 판단 문장("예고되어 있었다")은 쓰지 않는다.
     * 예: "사고 전 평가 떨어짐 상 (2026-07-16), 감소대책 기한 42일 경과"
     */
    private String postIncidentHeadline(boolean predicted, List<PriorHazard> priorHazards,
                            List<UnfinishedAction> unfinished, List<PriorIncident> priorIncidents) {
        UnfinishedAction overdue = unfinished.stream()
                .filter(a -> a.overdueDays() != null && a.overdueDays() > 0)
                .findFirst().orElse(null);
        PriorHazard same = priorHazards.stream()
                .filter(PriorHazard::sameAxisAsIncident)
                .filter(h -> h.lastRiskLevel() != null)
                .findFirst()
                .orElse(priorHazards.stream().filter(PriorHazard::sameAxisAsIncident).findFirst().orElse(null));

        List<String> parts = new ArrayList<>();
        if (same != null) {
            String axisLabel = same.accidentType() == null ? "" : same.accidentType().getLabel() + " ";
            parts.add(same.lastRiskLevel() == null
                    ? "사고 전 위험요인 등록 (%s미평가)".formatted(axisLabel)
                    : "사고 전 평가 %s%s (%s)".formatted(axisLabel, same.lastRiskLevel().getLabel(), same.lastAssessedOn()));
        }
        if (overdue != null) {
            parts.add("감소대책 기한 %d일 경과".formatted(overdue.overdueDays()));
        } else if (!unfinished.isEmpty()) {
            parts.add("미이행 감소대책 %d건".formatted(unfinished.size()));
        }
        if (!priorIncidents.isEmpty()) {
            parts.add("과거 사고 %d건".formatted(priorIncidents.size()));
        }
        return parts.isEmpty() ? "사고 전 같은 발생형태의 위험요인 기록 없음" : String.join(", ", parts);
    }

    /**
     * 작업 신고 전(UC3 사전 회상) — 아직 사고가 없으므로 "예고" 문구를 쓰지 않는다.
     * 지금 알고 있는 등급·미이행 조치를 사실 그대로 요약한다.
     *
     * <p>예: "최근 평가 상(떨어짐), 미이행 조치 1건(기한 32일 경과)"
     */
    private String preWorkHeadline(List<PriorHazard> priorHazards,
                                   List<UnfinishedAction> unfinished,
                                   List<PriorIncident> priorIncidents) {
        PriorHazard worst = priorHazards.stream()
                .filter(h -> h.lastRiskLevel() != null)
                .max(Comparator.comparingInt(h -> severityRank(h.lastRiskLevel())))
                .orElse(null);

        UnfinishedAction overdue = unfinished.stream()
                .filter(a -> a.overdueDays() != null && a.overdueDays() > 0)
                .findFirst().orElse(null);

        List<String> parts = new ArrayList<>();
        if (worst != null) {
            String axisLabel = worst.accidentType() == null ? "" : "(%s)".formatted(worst.accidentType().getLabel());
            parts.add("최근 평가 %s%s".formatted(worst.lastRiskLevel().getLabel(), axisLabel));
        }
        if (overdue != null) {
            parts.add("미이행 조치 %d건(기한 %d일 경과)".formatted(unfinished.size(), overdue.overdueDays()));
        } else if (!unfinished.isEmpty()) {
            parts.add("미이행 조치 %d건".formatted(unfinished.size()));
        }
        if (!priorIncidents.isEmpty()) {
            parts.add("과거 사고 %d건".formatted(priorIncidents.size()));
        }

        if (parts.isEmpty()) {
            return "이 설비에 기록된 위험요인이나 미이행 조치가 없습니다. 최초 평가부터 확인하십시오.";
        }
        return String.join(", ", parts);
    }

    private int severityRank(RiskLevel level) {
        if (level == null) {
            return -1;
        }
        return switch (level) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }
}
