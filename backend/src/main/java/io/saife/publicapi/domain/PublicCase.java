package io.saife.publicapi.domain;

import io.saife.core.domain.AccidentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 공공 사례 캐시.
 *
 * <p>무대에서 외부 API를 호출하지 않기 위한 전제다. 전량 내려받아 여기 적재하고
 * 런타임에는 이 테이블만 읽는다.
 *
 * <p>소스 우선순위: DISTER(1060, 6372건, 업종 필드 있음)가 1차,
 * FATALITY(1040, 2940건, 사망사고만)는 "이 위험요인으로 사람이 죽었다"를 말할 때 쓴다.
 */
@Entity
@Table(name = "public_case")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PublicCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FATALITY(callApiId=1040) / DISASTER(callApiId=1060) */
    @Column(nullable = false, length = 30)
    private String source;

    /** arno 또는 boardno */
    @Column(name = "source_key", nullable = false, length = 80)
    private String sourceKey;

    /** 업종. DISASTER만 제공한다 (건설 39% / 제조 32% / 서비스 20% / 조선 8.4%) */
    @Column(length = 50)
    private String business;

    /** 정규화된 한 줄 요약. 매칭의 주력 필드 */
    @Column(columnDefinition = "text")
    private String keyword;

    @Column(columnDefinition = "text")
    private String contents;

    @Enumerated(EnumType.STRING)
    @Column(name = "accident_type", length = 30)
    private AccidentType accidentType;

    @Column(length = 50)
    private String region;

    @Column(name = "occurred_on")
    private LocalDate occurredOn;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @PrePersist
    void onCreate() {
        this.fetchedAt = OffsetDateTime.now();
    }
}
