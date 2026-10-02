package io.saife.core.action;

/**
 * 감소대책 우선순위. 「사업장 위험성평가에 관한 지침」(고용노동부고시 제2024-76호) 제12조.
 *
 * <p>순서가 곧 우선순위다: 위험요인 제거, 대체 → 공학적 대책 → 관리적 대책 → 개인 보호구.
 * 보호구는 마지막 수단이라 다른 대책을 대신하지 못한다.
 */
public enum ControlPriority {
    ELIMINATION("제거"),
    ENGINEERING("공학적"),
    ADMINISTRATIVE("관리적"),
    PPE("보호구");

    private final String label;

    ControlPriority(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** DB 문자열 → 값. 모르는 값이나 null은 null(우선순위 미기재) */
    public static ControlPriority parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
