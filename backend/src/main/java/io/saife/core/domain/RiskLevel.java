package io.saife.core.domain;

/** 위험성 등급. LLM이 아니라 룰 엔진이 결정한다. */
public enum RiskLevel {
    HIGH("상"), MEDIUM("중"), LOW("하");

    private final String label;

    RiskLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
