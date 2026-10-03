package io.saife.core.action;

import io.saife.common.error.ApiExceptions.InvalidRequestException;

/**
 * 개선대책 목록 필터. 화면 탭과 1:1이다.
 *
 * <p>기한 경과 판정은 {@code TodayService}, {@code EquipmentTimelineService}와 같다:
 * {@code status = OVERDUE}이거나, 아직 DONE이 아닌데 기한이 KST 오늘보다 앞이다.
 */
public enum ActionListFilter {
    /** 완료가 아닌 전부(기한 경과 포함) */
    OPEN,
    /** 기한 경과만 */
    OVERDUE,
    /** 이행 완료 */
    DONE,
    /** 전부 */
    ALL;

    /** 비우면 OPEN. 모르는 값은 400 */
    public static ActionListFilter parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPEN;
        }
        try {
            return valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("status는 OPEN, OVERDUE, DONE, ALL 중 하나입니다.");
        }
    }
}
