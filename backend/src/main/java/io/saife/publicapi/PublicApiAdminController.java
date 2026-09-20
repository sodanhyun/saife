package io.saife.publicapi;

import io.saife.publicapi.domain.CrawlCheckpoint;
import io.saife.publicapi.service.PublicApiCrawler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 공공 API 캐싱 — <b>운영자 도구다. 시연 중에 부르지 않는다.</b>
 *
 * <p>무대에서는 외부 호출이 0이어야 하고, 심사위원은 서비스키가 없다.
 * 저장소에는 이미 수집된 캐시가 동봉되므로 이 엔드포인트를 부를 일이 없다.
 * 데이터를 갱신하고 싶을 때만 쓴다.
 */
@RestController
@RequestMapping("/api/admin/public-api")
@RequiredArgsConstructor
@Slf4j
public class PublicApiAdminController {

    private final PublicApiCrawler crawler;

    /** 수집 진행 상황. 중단됐으면 어느 페이지까지 받았는지 보인다 */
    @GetMapping("/status")
    public ResponseEntity<List<CrawlCheckpoint>> status() {
        return ResponseEntity.ok(crawler.status());
    }

    /** 한 데이터셋 수집. 이미 받은 페이지는 건너뛴다 */
    @PostMapping("/crawl/{dataset}")
    public ResponseEntity<PublicApiCrawler.CrawlReport> crawl(@PathVariable String dataset) {
        return ResponseEntity.ok(crawler.crawl(dataset));
    }

    @PostMapping("/crawl")
    public ResponseEntity<List<PublicApiCrawler.CrawlReport>> crawlAll() {
        return ResponseEntity.ok(crawler.crawlAll());
    }
}
