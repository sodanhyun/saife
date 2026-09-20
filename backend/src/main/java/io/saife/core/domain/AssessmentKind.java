package io.saife.core.domain;

/**
 * 평가 종류. 산업안전보건법 제36조 및 고용노동부고시 제2023-19호.
 *
 * 상시평가(ROUTINE)로 수시·정기를 면제받으려면 세 가지를 모두 채워야 한다:
 * 월 1회 순회점검 + 주 1회 관계자 논의 + <b>매 작업일 TBM</b>.
 * TBM 요건은 UC3의 작업 브리핑 카드(work_plan.briefing_ack_at)가 충족한다.
 */
public enum AssessmentKind {
    INITIAL("최초"),      // 사업 성립 1개월 내 착수
    OCCASIONAL("수시"),   // 설비 변경·신규 도입, 산재 발생 시 작업 재개 전
    REGULAR("정기"),      // 연 1회 적정성 재검토
    ROUTINE("상시");      // 월 1회 순회점검 등

    private final String label;

    AssessmentKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
