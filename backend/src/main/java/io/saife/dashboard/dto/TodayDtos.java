package io.saife.dashboard.dto;

import io.saife.dashboard.dto.TimelineDtos.Emphasis;

import java.time.LocalDate;
import java.util.List;

/**
 * "오늘 할 일" DTO — <b>기억이 만든 인박스.</b>
 *
 * <p>설비 홈 최상단에서 사용자가 행동하려는 순간 시스템이 아는 것을 먼저 말한다
 * (설계서 원칙 1). 7종 규칙({@code TodayService})이 각자 만든 항목을 한 목록으로
 * 합쳐 정렬·상한을 적용한 결과가 이 뷰다.
 *
 * <p>프론트 {@code src/types/timeline.ts}의 {@code TodayView}/{@code TodayItem}과
 * 1:1(camelCase). 필드 하나라도 바뀌면 그 타입도 같은 커밋에서 고친다.
 */
public final class TodayDtos {

    private TodayDtos() {}

    /**
     * @param asOf 오늘 날짜(KST). 시드·타임존이 바뀌어도 화면이 "오늘"을 무엇으로
     *             봤는지 그대로 드러낸다
     * @param items 정렬·상한(20건) 적용 완료. 프론트는 재정렬하지 않는다
     * @param criticalCount items 안의 CRITICAL 건수. warningCount와 함께 items와
     *                      항상 일치해야 한다 — 프론트가 이 두 값을 다시 계산하지 않고 그대로 믿는다
     */
    public record TodayView(LocalDate asOf, List<TodayItem> items,
                            int criticalCount, int warningCount) {}

    /**
     * 인박스 한 줄.
     *
     * @param kind OVERDUE_ACTION / DUE_ACTION / RISKY_WORK_PLAN / PENDING_APPROVAL /
     *             REPORT_DUE / PATROL_DUE / PERIODIC_DUE
     * @param daysRemaining 기한까지 남은 일수. <b>음수 허용</b>(지난 기한은 음수, 오늘은 0).
     *                      프론트 D-day 텍스트가 이 부호를 그대로 신뢰한다. 판단 기준이
     *                      없는 항목(예: 설비 단위가 아닌 정기점검류)은 null
     * @param linkType EQUIPMENT / WORK_PLAN / INCIDENT / ASSESSMENT — 프론트 라우팅 표의 키
     * @param refId linkType이 가리키는 레코드의 id. 없으면 null(프론트는 그러면 설비 상세로 접는다)
     */
    public record TodayItem(String kind, Emphasis emphasis, String title, String detail,
                            Long equipmentId, String equipmentName,
                            LocalDate dueDate, Long daysRemaining,
                            String linkType, Long refId) {}
}
