package io.saife.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 위험성평가. 최초·수시·정기·상시 4종. */
@Entity
@Table(name = "assessment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Assessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssessmentKind kind;

    /** EQUIPMENT_CHANGE / INCIDENT / SCHEDULE / PATROL */
    @Column(name = "trigger_type", length = 40)
    private String triggerType;

    /** 트리거가 된 레코드 id (사고 id 등) */
    @Column(name = "trigger_ref_id")
    private Long triggerRefId;

    @Column(name = "assessed_on", nullable = false)
    private LocalDate assessedOn;

    @Column(columnDefinition = "text")
    private String participants;

    /** DRAFT / CONFIRMED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
        if (this.status == null) {
            this.status = "DRAFT";
        }
    }

    public void confirm() {
        this.status = "CONFIRMED";
    }
}
