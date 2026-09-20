package io.saife.ai.agent.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 중단된 턴의 상태.
 *
 * <p><b>되묻기 턴은 HTTP 경계를 넘는다.</b> 작업자의 답변은 별도 POST로 들어오므로
 * 메시지 배열을 여기 영속화하고 스레드를 놓아준다.
 *
 * <p>⚠️ 워커 스레드가 {@code SseEmitter}를 붙잡은 채 사람이 타이핑하기를 기다리면
 * 스레드 풀이 마르고 무대에서 데모가 죽는다. 그러라고 있는 테이블이다.
 *
 * <p>{@code lastSeq}는 재개 후 seq를 이어서 증가시키기 위한 것이다. 리셋하면
 * 프론트 트레이스 패널의 순서가 무너진다.
 */
@Entity
@Table(name = "conversation_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ConversationState {

    @Id
    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    /** 직렬화된 메시지 배열 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "messages_json", nullable = false, columnDefinition = "jsonb")
    private String messagesJson;

    @Column(name = "pending_slot", length = 60)
    private String pendingSlot;

    @Column(name = "last_seq", nullable = false)
    private int lastSeq;

    @Column(name = "suspended_at")
    private OffsetDateTime suspendedAt;

    /** 타임아웃 시각. 초과하면 ABANDONED로 마킹한다 */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    public void suspend(String messagesJson, String pendingSlot, int lastSeq, OffsetDateTime expiresAt) {
        this.messagesJson = messagesJson;
        this.pendingSlot = pendingSlot;
        this.lastSeq = lastSeq;
        this.suspendedAt = OffsetDateTime.now();
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt != null && OffsetDateTime.now().isAfter(expiresAt);
    }
}
