package io.saife.dashboard.dto;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;
import io.saife.incident.service.EquipmentHistoryRecaller;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * UC4 설비 타임라인 DTO — <b>논지를 그림 하나로 만드는 구조.</b>
 *
 * <p>평가 → 작업계획서 → 사고 → 재평가가 하나의 설비 ID 위에서 이어진다는 것을
 * 수평선 하나로 보인다. 슬라이드 8의 머니샷이고 시연 영상의 마지막 컷이다.
 *
 * <p>프론트 {@code src/types/timeline.ts}와 1:1.
 */
public final class TimelineDtos {

    private TimelineDtos() {}

    public record EquipmentTimeline(EquipmentHead equipment,
                                    TimelineSummary summary,
                                    List<TimelineEvent> events) {}

    public record EquipmentHead(Long id,
                                String name,
                                String locationTag,
                                String processName,
                                String objectCode,
                                LocalDate introducedOn) {}

    /**
     * 타임라인 위쪽 요약.
     *
     * @param headline 한 줄 요약. 프로젝터에서 이 줄만 읽혀도 논지가 전달돼야 한다
     */
    public record TimelineSummary(RiskLevel currentRiskLevel,
                                  AccidentType currentRiskAxis,
                                  LocalDate lastAssessedOn,
                                  int assessmentCount,
                                  int workPlanCount,
                                  int incidentCount,
                                  int unfinishedActionCount,
                                  int overdueActionCount,
                                  String headline) {}

    /**
     * 타임라인의 한 점.
     *
     * @param id             {@code "incident-1"} 형태. 연결선의 양 끝을 잇는 키
     * @param linkedEventIds 이 사건이 가리키는 다른 사건들. <b>선이 그려지는 근거다</b>
     * @param linkedLabels   그 사건들을 사람이 읽는 말로. 화면에 내부 식별자를 띄우지 않는다
     * @param causalOrder    같은 날짜 안에서의 인과 순서. 날짜만으로 정렬하면
     *                       사고와 그 사고가 만든 수시평가가 같은 날일 때 순서가 뒤집힌다
     * @param emphasis       강조 수준. 프로젝터 가독성 때문에 <b>백엔드가 정한다</b> —
     *                       화면마다 다르게 판단하면 시연에서 색이 흔들린다
     */
    public record TimelineEvent(String id,
                                EventType type,
                                LocalDate at,
                                OffsetDateTime occurredAt,
                                String title,
                                String detail,
                                RiskLevel riskLevel,
                                AccidentType accidentType,
                                String status,
                                Long refId,
                                List<String> linkedEventIds,
                                List<String> linkedLabels,
                                int causalOrder,
                                Emphasis emphasis) {}

    /**
     * 설비 홈 카드 — 설비 하나의 현재 상태를 한 장으로.
     *
     * <p>등급·미이행·사고 수는 반드시 {@link #summary()}(같은 설비의 {@code timeline(id).summary()})와
     * 같아야 한다. 화면 두 곳(홈·상세)이 같은 설비에 다른 숫자를 보이면 심사위원이 바로 짚는다.
     *
     * @param unfinishedActionCount 미이행 조치 수 (기한 초과 포함)
     * @param overdueActionCount    그중 기한이 지난 것
     * @param upcomingWorkPlanCount 상태가 SUBMITTED/APPROVED/CONDITIONAL이고 작업일이 오늘 이후인 작업계획서 수
     * @param lastEventOn           타임라인 마지막 사건 날짜. 사건이 없으면 null
     * @param emphasis              카드 톤. <b>백엔드가 정한다</b> — 화면마다 다르게 판단하면 시연에서 색이 흔들린다
     * @param headline              카드 한 줄. {@code TimelineSummary.headline}과 같은 생성기를 쓴다
     */
    public record EquipmentCard(Long id, String name, String locationTag, String processName,
                                RiskLevel currentRiskLevel, AccidentType currentRiskAxis,
                                LocalDate lastAssessedOn,
                                int unfinishedActionCount, int overdueActionCount,
                                int upcomingWorkPlanCount,
                                int incidentCount,
                                LocalDate lastEventOn,
                                Emphasis emphasis,
                                String headline) {}

    /**
     * 설비 회상 뷰 — {@code IncidentDtos.RecallView}와 같은 구조에
     * {@code knownSlots}를 더한 공용 DTO다.
     *
     * <p>사고 등록(UC2)과 작업 신고 진입(UC3의 사전 회상)이 같은 소환 로직
     * ({@code EquipmentHistoryRecaller})을 쓰므로 응답 모양도 하나로 둔다.
     * {@code IncidentDtos.RecallView}는 이 레코드를 그대로 재사용한다 —
     * {@code knownSlots} 필드가 {@code /api/incident} 응답에도 추가로 실리지만
     * 기존 필드는 그대로라 프론트 {@code types/incident.ts}가 깨지지 않는다(additive).
     *
     * @param knownSlots 이 설비에 대해 시스템이 이미 아는 항목의 사람 말.
     *                   {@code ["장소","설비","공정/작업유형","최근 평가 등급","미이행 조치"]} 중
     *                   실제로 값이 있는 것만 담는다 — 값이 없는데 "안다"고 말하면 신뢰가 깨진다
     */
    public record RecallView(Long equipmentId,
                             String equipmentName,
                             String locationTag,
                             String headline,
                             boolean predicted,
                             OffsetDateTime warnedAt,
                             List<EquipmentHistoryRecaller.PriorHazard> priorHazards,
                             List<EquipmentHistoryRecaller.UnfinishedAction> unfinishedActions,
                             List<EquipmentHistoryRecaller.PriorWorkPlan> priorWorkPlans,
                             List<EquipmentHistoryRecaller.PriorIncident> priorIncidents,
                             List<String> knownSlots) {

        /** 사고 등록(UC2) 경로 — 공정/작업유형은 이 문맥에서 쓰지 않는다 */
        public static RecallView from(EquipmentHistoryRecaller.Recall r) {
            return from(r, null);
        }

        /** 설비 홈·작업 신고 진입(UC3 사전 회상) 경로 — 공정명까지 알려진 상태다 */
        public static RecallView from(EquipmentHistoryRecaller.Recall r, String processName) {
            return new RecallView(r.equipmentId(), r.equipmentName(), r.locationTag(),
                    r.headline(), r.predicted(), r.warnedAt(),
                    r.priorHazards(), r.unfinishedActions(),
                    r.priorWorkPlans(), r.priorIncidents(),
                    knownSlots(r, processName));
        }

        private static List<String> knownSlots(EquipmentHistoryRecaller.Recall r, String processName) {
            List<String> slots = new ArrayList<>();
            if (r.locationTag() != null && !r.locationTag().isBlank()) {
                slots.add("장소");
            }
            if (r.equipmentName() != null && !r.equipmentName().isBlank()
                    && !"(미등록 설비)".equals(r.equipmentName())) {
                slots.add("설비");
            }
            if (processName != null && !processName.isBlank()) {
                slots.add("공정/작업유형");
            }
            if (r.priorHazards().stream().anyMatch(h -> h.lastRiskLevel() != null)) {
                slots.add("최근 평가 등급");
            }
            if (!r.unfinishedActions().isEmpty()) {
                slots.add("미이행 조치");
            }
            return slots;
        }
    }

    public enum EventType {
        ASSESSMENT("위험성평가"),
        ACTION("감소대책"),
        WORK_PLAN("작업계획서"),
        INCIDENT("산업재해");

        private final String label;

        EventType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** NORMAL 회색 · WARNING 주황 · CRITICAL 빨강 */
    public enum Emphasis {
        NORMAL, WARNING, CRITICAL
    }
}
