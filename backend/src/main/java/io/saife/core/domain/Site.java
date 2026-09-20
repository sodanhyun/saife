package io.saife.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** 사업장. 이번 범위에서는 가상 사업장 1곳만 사용한다. */
@Entity
@Table(name = "site")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** 업종 중분류 */
    @Column(name = "industry_code", length = 20)
    private String industryCode;

    /** 상시근로자 수. 50인 미만 제조업이면 산재예방요율제 대상 */
    @Column(name = "worker_count")
    private Integer workerCount;

    @Column(length = 500)
    private String address;

    /** 상시평가 트랙 운영 여부 */
    @Column(name = "regular_track", nullable = false)
    private boolean regularTrack;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
