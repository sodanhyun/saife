package io.saife.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** 공정 / 라인 / 장소. 위치 태그가 자연어 매칭의 키가 된다. */
@Entity
@Table(name = "process")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Process {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(nullable = false, length = 200)
    private String name;

    /** 작업 유형 (도장, 용접, 조립 …) */
    @Column(name = "work_type", length = 100)
    private String workType;

    /** 위치 태그. 예: 공장동 후면 차양부 */
    @Column(name = "location_tag", length = 200)
    private String locationTag;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
