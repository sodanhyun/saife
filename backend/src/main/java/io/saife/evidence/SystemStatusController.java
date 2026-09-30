package io.saife.evidence;

import io.saife.common.config.DemoModeConfig;
import io.saife.evidence.live.LiveOrCache;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.evidence.search.QueryEmbedder;
import io.saife.publicapi.domain.CrawlCheckpoint;
import io.saife.publicapi.repository.CrawlCheckpointRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프론트 전역 상태 줄용. 데모 모드·벡터 가능 여부·근거 수·회로 상태를 한 번에 묻는다.
 *
 * <p>{@code lastCrawlAt}은 {@code crawl_checkpoint} 테이블에서 뽑는다. 이 테이블은
 * {@code PublicApiCrawler}(dataset: FATALITY/GUIDE/DISASTER/MSDS)와 {@code IndexBuilder}
 * (dataset: {@code INDEX_*})가 같이 쓴다 — 인덱스 재구축은 "공공데이터 수집"이 아니므로
 * {@code INDEX_} 접두사 데이터셋은 제외하고, 완료(DONE)된 것 중 가장 최근 갱신 시각만 본다.
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemStatusController {

    /** 프론트 타입과 1:1 매핑 대상(B2 계획). 필드명은 camelCase 그대로 Jackson 직렬화된다 */
    public record SystemStatus(boolean demoMode,
                                boolean embeddingAvailable,
                                long evidenceChunkCount,
                                Map<String, Long> evidenceByKind,
                                Set<String> circuitOpenHosts,
                                OffsetDateTime lastCrawlAt) {}

    private final DemoModeConfig demoModeConfig;
    private final QueryEmbedder embedder;
    private final EvidenceChunkRepository chunks;
    private final LiveOrCache liveOrCache;
    private final CrawlCheckpointRepository checkpoints;

    @GetMapping("/status")
    public ResponseEntity<SystemStatus> status() {
        Map<String, Long> byKind = chunks.countByKind();
        long total = byKind.values().stream().mapToLong(Long::longValue).sum();
        OffsetDateTime lastCrawlAt = checkpoints.findAll().stream()
                .filter(c -> CrawlCheckpoint.STATUS_DONE.equals(c.getStatus()))
                .filter(c -> c.getDataset() != null && !c.getDataset().startsWith("INDEX_"))
                .map(CrawlCheckpoint::getUpdatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return ResponseEntity.ok(new SystemStatus(demoModeConfig.isDemoMode(), embedder.available(),
                total, byKind, liveOrCache.openHosts(), lastCrawlAt));
    }
}
