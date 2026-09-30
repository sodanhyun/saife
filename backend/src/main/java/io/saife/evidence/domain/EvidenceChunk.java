package io.saife.evidence.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 검색 청크. 벡터(embedding)와 tsv는 JdbcTemplate로만 다룬다 — 엔티티는 텍스트·메타만 */
@Entity
@Table(name = "evidence_chunk")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EvidenceChunk {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20) private String kind;
    @Column(name = "ref_id", nullable = false) private Long refId;
    @Column(name = "ref_key", nullable = false, length = 120) private String refKey;
    @Column(name = "chunk_level", nullable = false, length = 10) private String chunkLevel;
    @Column(name = "parent_id") private Long parentId;
    @Column(nullable = false) private boolean searchable;
    @Column(name = "section_title", length = 300) private String sectionTitle;
    @Column(nullable = false, length = 500) private String title;
    @Column(nullable = false, columnDefinition = "text") private String text;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private String metadata;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist void onCreate() { if (updatedAt == null) updatedAt = OffsetDateTime.now(); }
}
