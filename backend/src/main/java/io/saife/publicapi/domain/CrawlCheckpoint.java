package io.saife.publicapi.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 공공 API 수집 체크포인트.
 *
 * <p>쿼터가 KST 자정에 리셋된다. 2,940건을 받다가 2,000건째에 끊기면 다시 처음부터
 * 받아야 하고, 그러면 그날 하루를 날린다. <b>페이지마다 커밋한다.</b>
 */
@Entity
@Table(name = "crawl_checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CrawlCheckpoint {

    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    /** FATALITY / GUIDE / DISASTER / MSDS */
    @Id
    @Column(length = 30)
    private String dataset;

    /** 저장까지 끝난 마지막 페이지. 다음 실행은 여기 +1부터 */
    @Column(name = "last_page", nullable = false)
    private int lastPage;

    @Column(name = "total_count")
    private Integer totalCount;

    @Column(name = "saved_count", nullable = false)
    private int savedCount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
        if (this.status == null) {
            this.status = STATUS_IDLE;
        }
    }
}
