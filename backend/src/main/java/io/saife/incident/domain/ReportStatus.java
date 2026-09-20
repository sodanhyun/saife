package io.saife.incident.domain;

/**
 * 산업재해조사표 제출 상태.
 *
 * <p>휴업 3일 이상이면 발생일로부터 <b>1개월 이내</b> 관할 지방고용노동관서에 제출해야 한다
 * (산업안전보건법 시행규칙 제73조). 이 타이머가 "법적 의무" 계층을 화면으로 증명하는
 * 유일한 요소다.
 */
public enum ReportStatus {
    /**
     * 휴업일수가 입력되지 않아 판단할 수 없다.
     *
     * <p><b>미입력을 "의무 없음"으로 읽지 않는다.</b> 모르는 것과
     * 없는 것은 다르고, 법정 의무를 조용히 면제해 버리는 기본값은 위험하다.
     */
    UNDETERMINED("판단 보류"),
    /** 휴업 3일 미만 — 제출 의무 없음 */
    NOT_REQUIRED("제출 의무 없음"),
    /** 제출 의무 있음, 기한 내 */
    REQUIRED("제출 필요"),
    /** 기한 경과 */
    OVERDUE("기한 경과"),
    /** 제출 완료 */
    SUBMITTED("제출 완료");

    private final String label;

    ReportStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
