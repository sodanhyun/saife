package io.saife.ai.agent.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 도구 호출 트레이스.
 * 화면의 트레이스 패널과 성과 지표(도구 체인 완주율)가 같은 테이블을 본다 —
 * 두 숫자가 어긋나지 않도록 출처를 하나로 둔다.
 */
@Entity
@Table(name = "tool_call")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ToolCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(name = "call_order", nullable = false)
    private int callOrder;

    @Column(name = "tool_name", nullable = false, length = 80)
    private String toolName;

    @Column(name = "params_json", columnDefinition = "text")
    private String paramsJson;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "called_at", nullable = false)
    private OffsetDateTime calledAt;

    @PrePersist
    void onCreate() {
        this.calledAt = OffsetDateTime.now();
    }
}
