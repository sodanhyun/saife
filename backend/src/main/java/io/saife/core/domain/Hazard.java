package io.saife.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 위험요인.
 *
 * <p>{@code aiSuggested}/{@code aiAdopted}가 성과 지표(후보 채택률)의 원천이다.
 * AI가 제안하고 사람이 확정한 비율을 여기서 센다 — 정확도를 주장하지 않는다.
 */
@Entity
@Table(name = "hazard")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Hazard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "equipment_id")
    private Long equipmentId;

    @Column(name = "process_id")
    private Long processId;

    @Enumerated(EnumType.STRING)
    @Column(name = "accident_type", nullable = false, length = 30)
    private AccidentType accidentType;

    /** 빠진 안전조치. 사진 판독의 실제 출력이다 */
    @Column(name = "missing_control", length = 200)
    private String missingControl;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HazardSource source;

    @Column(name = "photo_path", length = 500)
    private String photoPath;

    /** AI가 후보로 올렸는지 */
    @Column(name = "ai_suggested", nullable = false)
    private boolean aiSuggested;

    /** 사람이 채택했는지. null이면 아직 판단 전 */
    @Column(name = "ai_adopted")
    private Boolean aiAdopted;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public void adopt() {
        this.aiAdopted = Boolean.TRUE;
    }

    public void reject() {
        this.aiAdopted = Boolean.FALSE;
    }
}
