package io.saife.core.action;

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
     * @param assessmentId 이 대책을 확정한 평가. 비우면 그 위험요인이 등장한 가장 최근 평가에 묶는다
     * @param guideRef     KOSHA GUIDE 규정번호. 화면이 제안 문안({@code suggestedAction.guideRef})을 그대로 넘긴다
     */
    public record CreateActionRequest(Long assessmentId, String content, String owner,
                                      LocalDate dueDate, String guideRef) {}

    /**
     * 조치 한 건.
     *
     * @param equipmentId 이 조치가 걸린 설비. 화면이 "이 설비 타임라인에 기록됨" 링크를 만든다
     */
    public record ActionView(Long id, Long hazardId, Long assessmentId, Long equipmentId,
                             String content, String owner, LocalDate dueDate,
                             ActionStatus status, String guideRef,
                             OffsetDateTime completedAt, OffsetDateTime createdAt) {

        public static ActionView of(Action a, Long equipmentId) {
            return new ActionView(a.getId(), a.getHazardId(), a.getAssessmentId(), equipmentId,
                    a.getContent(), a.getOwner(), a.getDueDate(), a.getStatus(), a.getGuideRef(),
                    a.getCompletedAt(), a.getCreatedAt());
        }
    }

    /**
     * 감소대책 초안. {@link ActionSuggestionTable}이 축과 빠진 조치로 고른다(모델 호출 없음).
     *
     * @param lawRef   근거 조문 (예: "산업안전보건기준에 관한 규칙 제44조")
     * @param lawTitle 조문 제목 요약
     * @param guideRef 후보 근거 카드에 붙은 KOSHA GUIDE 번호. 없으면 null
     */
    public record SuggestedAction(String content, String lawRef, String lawTitle, String guideRef) {

        public static SuggestedAction of(ActionSuggestionTable.Suggestion s) {
            return s == null ? null : new SuggestedAction(s.content(), s.lawRef(), s.lawWhy(), s.guideRef());
        }
    }
}
