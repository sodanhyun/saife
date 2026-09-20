package io.saife.ai.agent.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** 에이전트 대화 세션. id가 곧 SSE correlationId다. */
@Entity
@Table(name = "conversation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Conversation {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "user_name", length = 100)
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = ConversationStatus.ACTIVE;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public void awaitSlot() {
        this.status = ConversationStatus.AWAITING_SLOT;
    }

    public void resume() {
        this.status = ConversationStatus.ACTIVE;
    }

    public void close() {
        this.status = ConversationStatus.CLOSED;
    }

    public void abandon() {
        this.status = ConversationStatus.ABANDONED;
    }
}
