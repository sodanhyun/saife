package io.saife.publicapi.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * KOSHA GUIDE 기술지침 캐시 (callApiId=1050, 전수 1039건).
 * 실측 확인: fileDownloadUrl이 실제로 온다 — 근거를 규정명·번호로 낮출 필요가 없다.
 */
@Entity
@Table(name = "kosha_guide")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class KoshaGuide {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** techGdlnNo */
    @Column(name = "guide_no", nullable = false, unique = true, length = 50)
    private String guideNo;

    /** techGdlnNm */
    @Column(name = "guide_name", nullable = false, length = 500)
    private String guideName;

    /** techGdlnOfancYmd */
    @Column(name = "announced_on")
    private LocalDate announcedOn;

    @Column(name = "file_download_url", length = 500)
    private String fileDownloadUrl;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @PrePersist
    void onCreate() {
        this.fetchedAt = OffsetDateTime.now();
    }
}
