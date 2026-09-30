package io.saife.evidence.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 대화에서 제시한 근거. 번호는 대화 전체에서 유일하다 */
@Entity
@Table(name = "conversation_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ConversationEvidence {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "conversation_id", nullable = false, length = 40) private String conversationId;
    @Column(name = "turn_no", nullable = false) private int turnNo;
    @Column(name = "evidence_no", nullable = false) private int evidenceNo;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private String payload;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = OffsetDateTime.now(); }
}
