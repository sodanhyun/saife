package io.saife.ai.agent.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 대화 히스토리.
 *
 * <p>초기 설계에서는 "중단된 턴의 상태"였다. 되묻기가 스레드 중단/재개를 요구한다고
 * 보고 메시지 배열·pendingSlot·lastSeq를 들고 있었다.
 *
 * <p>실측 결과 그 배관이 필요 없었다({@code docs/experiments/README.md}).
 * 모델이 질문을 내놓고 턴을 끝내는 것이 곧 일시정지이고, 사용자가 답하면 같은 대화 ID로
 * 다음 요청이 온다 — <b>평범한 멀티턴 대화</b>다. 그래서 이 테이블도 평범한 채팅 메모리다.
 *
 * <p>{@code pendingSlot}·{@code lastSeq}·{@code suspendedAt}·{@code expiresAt}는
 * 스키마에 남아 있으나 현재 흐름 제어에 쓰이지 않는다. 장시간 방치된 대화를 정리하는
 * 배치를 붙일 때 쓸 수 있다.
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

    /** 히스토리를 통째로 갈아끼운다 */
    public void replaceHistory(String messagesJson) {
        this.messagesJson = messagesJson;
        this.suspendedAt = OffsetDateTime.now();
    }

    public boolean isExpired() {
        return expiresAt != null && OffsetDateTime.now().isAfter(expiresAt);
    }
}
