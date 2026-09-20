package io.saife.dashboard.dto;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
