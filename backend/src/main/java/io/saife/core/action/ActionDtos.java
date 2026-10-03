package io.saife.core.action;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 감소대책(조치) API 계약. 프론트 {@code src/types/action.ts}와 1:1 */
public final class ActionDtos {

    private ActionDtos() {}

    /**
     * 감소대책 등록 요청.
     *
     * @param assessmentId 이 대책을 수립한 평가. 비우면 그 위험요인이 등장한 가장 최근 평가에 묶는다
     * @param guideRef     KOSHA GUIDE 규정번호. 화면이 제안 문안({@code suggestedAction.guideRef})을 그대로 넘긴다
     * @param priority     감소대책 우선순위(고시 제12조). 비우면 미기재
     */
    public record CreateActionRequest(Long assessmentId, String content, String owner,
                                      LocalDate dueDate, String guideRef, ControlPriority priority) {}

    /**
     * 조치 한 건.
     *
     * @param equipmentId 이 조치가 걸린 설비. 화면이 "설비 이력" 링크를 만든다
     * @param priority    감소대책 우선순위(고시 제12조). 미기재면 null
     */
    public record ActionView(Long id, Long hazardId, Long assessmentId, Long equipmentId,
                             String content, String owner, LocalDate dueDate,
                             ActionStatus status, String guideRef,
                             OffsetDateTime completedAt, OffsetDateTime createdAt,
                             ControlPriority priority) {

        public static ActionView of(Action a, Long equipmentId) {
            return of(a, equipmentId, null);
        }

        public static ActionView of(Action a, Long equipmentId, ControlPriority priority) {
            return new ActionView(a.getId(), a.getHazardId(), a.getAssessmentId(), equipmentId,
                    a.getContent(), a.getOwner(), a.getDueDate(), a.getStatus(), a.getGuideRef(),
                    a.getCompletedAt(), a.getCreatedAt(), priority);
        }
    }

    /**
     * 개선대책 목록 한 줄. {@code GET /api/action}.
     *
     * @param status         판정 상태. 기한이 지난 미완료는 저장값과 무관하게 OVERDUE
     * @param overdueDays    기한 경과 일수(KST 오늘 기준). 기한 경과가 아니면 null
     * @param priority       감소대책 우선순위(고시 제12조). 미기재면 null
     * @param accidentType   위험요인의 발생형태
     * @param missingControl 위험요인의 빠진 안전조치
     */
    public record ActionListItem(Long id, String content, String owner, LocalDate dueDate,
                                 ActionStatus status, Long overdueDays, OffsetDateTime completedAt,
                                 ControlPriority priority, String guideRef, Long hazardId,
                                 AccidentType accidentType, String missingControl,
                                 Long equipmentId, String equipmentName, Long assessmentId) {}

    /** 목록 탭 건수. open은 기한 경과를 포함한다 */
    public record ActionCounts(long open, long overdue, long done) {}

    /**
     * 감소대책 초안. {@link ActionSuggestionTable}이 발생형태와 빠진 조치로 고른다(모델 호출 없음).
     *
     * @param lawRef   근거 조문 (예: "산업안전보건기준에 관한 규칙 제42조제4항")
     * @param lawTitle 조문 제목
     * @param guideRef 후보 근거 카드에 붙은 KOSHA GUIDE 번호. 없으면 null
     * @param priority 이 문안의 감소대책 우선순위(고시 제12조)
     */
    public record SuggestedAction(String content, String lawRef, String lawTitle, String guideRef,
                                  ControlPriority priority) {

        public static SuggestedAction of(ActionSuggestionTable.Suggestion s) {
            return s == null ? null
                    : new SuggestedAction(s.content(), s.lawRef(), s.lawTitle(), s.guideRef(), s.priority());
        }
    }
}
