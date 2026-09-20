package io.saife.ai.agent.domain;

public enum ConversationStatus {
    ACTIVE,
    AWAITING_SLOT,   // 되묻기 턴 — 스트림이 멈추고 사용자 입력을 기다리는 중
    CLOSED,
    ABANDONED        // 타임아웃
}
