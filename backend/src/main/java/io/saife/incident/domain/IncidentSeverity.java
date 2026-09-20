package io.saife.incident.domain;

/**
 * 재해 정도 — <b>기록용 라벨이다. 법정 판정에 쓰지 않는다.</b>
 *
 * <p>중대재해 판정 로직은 의도적으로 만들지 않았다. 판정 기준이 복잡한 데 비해
 * 이 출품작에서 보상받지 못하고, 틀린 판정은 안 하느니만 못하다.
 * 산업재해조사표 제출 의무는 {@code leaveDays >= 3} 하나로만 결정한다
 * (산업안전보건법 시행규칙 제73조).
 */
public enum IncidentSeverity {
    NEAR_MISS("아차사고"),
    INJURY("부상"),
    LOST_TIME("휴업"),
    FATALITY("사망");

    private final String label;

    IncidentSeverity(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
