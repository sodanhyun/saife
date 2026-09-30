package io.saife.publicapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.core.domain.AccidentType;
import io.saife.publicapi.domain.CrawlCheckpoint;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.CrawlCheckpointRepository;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 공공 API 캐싱 크롤러 — <b>무대에서는 외부 호출이 0이어야 한다.</b>
 *
 * <p>수집한 것을 {@code public_case}·{@code kosha_guide}에 넣어두고, 시연에서는
 * 이 테이블만 읽는다. 네트워크가 죽어도 사례 검색이 돌아가야 한다.
 *
 * <p>세 가지를 반드시 지킨다({@code .claude/rules/public-api-integration.md}):
 *
 * <ol>
 *   <li><b>재개 가능.</b> 페이지마다 체크포인트를 커밋한다. 쿼터가 KST 자정 리셋이라
 *       중간 실패가 하루를 날린다</li>
 *   <li><b>응답 필드 검증.</b> B552468은 공유 게이트웨이라 {@code callApiId}가 틀리면
 *       에러가 아니라 <b>다른 데이터셋이 조용히 온다</b>. 검증 실패 시 저장하지 않고 멈춘다</li>
 *   <li><b>디코딩 키를 넣고 클라이언트가 인코딩하게 둔다.</b> 인코딩 키를 다시 인코딩하면 실패한다</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicApiCrawler {

    /** numOfRows. 300이면 사고사망 10회, KOSHA GUIDE 4회, 국내재해사례 22회 */
    private static final int PAGE_SIZE = 300;

    /** 상대 쪽 부하를 생각해서 페이지 사이에 쉰다 */
    private static final long PAGE_DELAY_MS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PublicCaseRepository publicCaseRepository;
    private final KoshaGuideRepository koshaGuideRepository;
    private final CrawlCheckpointRepository checkpointRepository;
    private final AccidentTypeClassifier classifier;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Value("${public-api.kosha.base-url}")
    private String baseUrl;

    @Value("${public-api.kosha.datasets.fatality.key:}")
    private String fatalityKey;

    @Value("${public-api.kosha.datasets.guide.key:}")
    private String guideKey;

    @Value("${public-api.kosha.datasets.disaster.key:}")
    private String disasterKey;

    /**
     * @param dataset    FATALITY / GUIDE / DISASTER
     * @param path       실제 경로. <b>오타와 비일관 케이싱이 그대로 배포돼 있다</b>
     * @param callApiId  진짜 데이터셋 선택자. 경로가 아니다
     * @param verifyField 첫 아이템에 반드시 있어야 하는 필드. 없으면 다른 데이터셋이 온 것
     */
    private record Dataset(String dataset, String path, String callApiId, String verifyField) {}

    private static final List<Dataset> DATASETS = List.of(
            new Dataset("FATALITY", "/news_api02/getNews_api02", "1040", "keyword"),
            new Dataset("GUIDE", "/koshaguide/getKoshaGuide", "1050", "techGdlnNo"),
            new Dataset("DISASTER", "/disaster_api02/getdisaster_api02", "1060", "business"));

    public record CrawlReport(String dataset, int savedCount, int totalCount,
                              int lastPage, String status, String message) {}

    /** 검증에 실패하면 저장하지 않고 즉시 멈춘다 — 잘못된 데이터를 5일간 모으는 것보다 낫다 */
    public static class DatasetVerificationException extends RuntimeException {
        public DatasetVerificationException(String message) {
            super(message);
        }
    }

    public List<CrawlReport> crawlAll() {
        List<CrawlReport> reports = new ArrayList<>();
        for (Dataset dataset : DATASETS) {
            reports.add(crawl(dataset.dataset()));
        }
        return reports;
    }

    /**
     * 한 데이터셋을 수집한다. 이미 받은 페이지는 건너뛴다.
     *
     * @param datasetName FATALITY / GUIDE / DISASTER
     */
    public CrawlReport crawl(String datasetName) {
        Dataset dataset = DATASETS.stream()
                .filter(d -> d.dataset().equalsIgnoreCase(datasetName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 데이터셋: " + datasetName));

        String key = keyFor(dataset.dataset());
        if (key == null || key.isBlank()) {
            return new CrawlReport(dataset.dataset(), 0, 0, 0, CrawlCheckpoint.STATUS_IDLE,
                    "서비스키가 없습니다. .env의 KOSHA_KEY_*를 설정하십시오. "
                            + "(심사용 저장소는 이미 수집된 캐시를 동봉하므로 실행할 필요가 없습니다)");
        }

        CrawlCheckpoint checkpoint = loadOrCreate(dataset.dataset());
        int page = checkpoint.getLastPage() + 1;
        int saved = checkpoint.getSavedCount();
        Integer total = checkpoint.getTotalCount();

        markRunning(dataset.dataset());
        log.info("[CRAWL] {} 시작 — {}페이지부터 (이미 {}건 저장됨)",
                dataset.dataset(), page, saved);

        try {
            while (true) {
                JsonNode body = fetch(dataset, key, page);
                if (total == null) {
                    total = body.path("totalCount").asInt(0);
                }
                List<JsonNode> items = items(body);
                if (items.isEmpty()) {
                    break;
                }

                verify(dataset, items.get(0));
                saved += save(dataset, items);

                // 페이지마다 커밋한다. 여기서 끊겨도 다음 실행이 이어받는다
                saveCheckpoint(dataset.dataset(), page, total, saved,
                        CrawlCheckpoint.STATUS_RUNNING, null);

                log.info("[CRAWL] {} p{} — 누적 {}/{}", dataset.dataset(), page, saved, total);

                if (items.size() < PAGE_SIZE || (total > 0 && page * PAGE_SIZE >= total)) {
                    break;
                }
                page++;
                sleep();
            }

            saveCheckpoint(dataset.dataset(), page, total, saved, CrawlCheckpoint.STATUS_DONE, null);
            log.info("[CRAWL] {} 완료 — {}건", dataset.dataset(), saved);
            return new CrawlReport(dataset.dataset(), saved, total == null ? 0 : total,
                    page, CrawlCheckpoint.STATUS_DONE, "완료");

        } catch (Exception e) {
            // 체크포인트는 마지막 성공 페이지에 남아 있다. 다시 부르면 거기서 이어간다
            saveCheckpoint(dataset.dataset(), page - 1, total, saved,
                    CrawlCheckpoint.STATUS_FAILED, e.toString());
            log.error("[CRAWL] {} 중단 p{} — 누적 {}건. 다시 실행하면 이어받는다",
                    dataset.dataset(), page, saved, e);
            return new CrawlReport(dataset.dataset(), saved, total == null ? 0 : total,
                    page - 1, CrawlCheckpoint.STATUS_FAILED,
                    "중단: " + e.getMessage() + " — 다시 실행하면 " + page + "페이지부터 이어받습니다");
        }
    }

    public List<CrawlCheckpoint> status() {
        return checkpointRepository.findAll();
    }

    /** 최신 등재 건수 확인용 응답 — 카드 메타 "공단 기준" 표시에 쓴다 */
    public record LatestInfo(int totalCount, OffsetDateTime checkedAt) {}

    /**
     * 공단에 최신 등재 건수만 확인한다(1페이지 1건). 도구 결과 텍스트에
     * "공단 최신 등재 N건 확인"으로 붙는다.
     *
     * <p>키가 없거나 실패하면 {@code Optional.empty()}다 — 무대에서 절대
     * 예외를 던지지 않고, 5초 타임아웃으로 도구를 막지 않는다.
     */
    public Optional<LatestInfo> checkLatest(String datasetName) {
        Dataset dataset = DATASETS.stream()
                .filter(d -> d.dataset().equalsIgnoreCase(datasetName))
                .findFirst()
                .orElse(null);
        String key = dataset == null ? null : keyFor(dataset.dataset());
        if (dataset == null || key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            String url = "%s%s?serviceKey=%s&callApiId=%s&numOfRows=1&pageNo=1&type=json".formatted(
                    baseUrl, dataset.path(), URLEncoder.encode(key, StandardCharsets.UTF_8), dataset.callApiId());
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(new LatestInfo(MAPPER.readTree(res.body()).path("body").path("totalCount").asInt(0),
                    OffsetDateTime.now()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ---------- 수집 ----------

    private JsonNode fetch(Dataset dataset, String key, int page) throws Exception {
        // 디코딩 키를 여기서 한 번 인코딩한다. 인코딩 키를 넣고 또 인코딩하면 실패한다
        String url = "%s%s?serviceKey=%s&callApiId=%s&numOfRows=%d&pageNo=%d&type=json".formatted(
                baseUrl, dataset.path(),
                URLEncoder.encode(key, StandardCharsets.UTF_8),
                dataset.callApiId(), PAGE_SIZE, page);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP %d — %s".formatted(
                    response.statusCode(), truncate(response.body())));
        }
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode body = root.path("body");
        if (body.isMissingNode()) {
            throw new IllegalStateException("응답에 body가 없습니다: " + truncate(response.body()));
        }
        return body;
    }

    private List<JsonNode> items(JsonNode body) {
        JsonNode item = body.path("items").path("item");
        List<JsonNode> out = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(out::add);
        } else if (item.isObject()) {
            out.add(item);
        }
        return out;
    }

    /**
     * 데이터셋 검증 — <b>이게 없으면 잘못된 데이터를 조용히 모은다.</b>
     *
     * <p>KOSHA GUIDE 경로에 {@code callApiId=1040}을 넣으면 200이 떨어지고
     * 사고사망 데이터가 온다. 에러가 아니다.
     */
    private void verify(Dataset dataset, JsonNode first) {
        if (!first.hasNonNull(dataset.verifyField())) {
            throw new DatasetVerificationException(
                    ("%s 응답에 '%s' 필드가 없습니다. callApiId=%s가 다른 데이터셋을 반환했을 수 있습니다. "
                            + "받은 필드: %s")
                            .formatted(dataset.dataset(), dataset.verifyField(),
                                    dataset.callApiId(), fieldNames(first)));
        }
    }

    private String fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names);
    }

    // ---------- 저장 ----------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int save(Dataset dataset, List<JsonNode> items) {
        int n = 0;
        for (JsonNode item : items) {
            n += switch (dataset.dataset()) {
                case "GUIDE" -> saveGuide(item);
                case "FATALITY" -> saveCase(item, "FATALITY", "arno");
                case "DISASTER" -> saveCase(item, "DISASTER", "boardno");
                default -> 0;
            };
        }
        return n;
    }

    private int saveGuide(JsonNode item) {
        String guideNo = text(item, "techGdlnNo");
        if (guideNo == null) {
            return 0;
        }
        if (koshaGuideRepository.findByGuideNo(guideNo).isPresent()) {
            return 0;
        }
        koshaGuideRepository.save(KoshaGuide.builder()
                .guideNo(guideNo)
                .guideName(truncate(text(item, "techGdlnNm"), 500))
                .announcedOn(parseDate(text(item, "techGdlnOfancYmd")))
                .fileDownloadUrl(truncate(text(item, "fileDownloadUrl"), 500))
                .fetchedAt(OffsetDateTime.now())
                .build());
        return 1;
    }

    private int saveCase(JsonNode item, String source, String keyField) {
        String sourceKey = text(item, keyField);
        if (sourceKey == null) {
            return 0;
        }
        if (publicCaseRepository.findBySourceAndSourceKey(source, sourceKey).isPresent()) {
            return 0;
        }

        String rawContents = text(item, "contents");
        String keyword = text(item, "keyword");
        String contents = CaseTextCleaner.clean(rawContents);
        String imageUrl = "FATALITY".equals(source) ? CaseTextCleaner.imageUrlOf(rawContents) : null;
        String sourceUrl = "DISASTER".equals(source) ? CaseTextCleaner.DISASTER_LIST_URL : null;
        AccidentType axis = classifier.classify(keyword, contents);

        publicCaseRepository.save(PublicCase.builder()
                .source(source)
                .sourceKey(truncate(sourceKey, 80))
                .business(truncate(text(item, "business"), 50))
                .keyword(keyword)
                .contents(contents)
                .accidentType(axis)
                .region(truncate(CaseTextCleaner.regionOf(keyword), 50))
                .occurredOn(CaseTextCleaner.occurredOn(keyword))
                .imageUrl(imageUrl)
                .sourceUrl(sourceUrl)
                .fetchedAt(OffsetDateTime.now())
                .build());
        return 1;
    }

    // ---------- 체크포인트 ----------

    private CrawlCheckpoint loadOrCreate(String dataset) {
        return checkpointRepository.findById(dataset).orElseGet(() ->
                checkpointRepository.save(CrawlCheckpoint.builder()
                        .dataset(dataset)
                        .lastPage(0)
                        .savedCount(0)
                        .status(CrawlCheckpoint.STATUS_IDLE)
                        .startedAt(OffsetDateTime.now())
                        .build()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(String dataset) {
        CrawlCheckpoint c = loadOrCreate(dataset);
        checkpointRepository.save(CrawlCheckpoint.builder()
                .dataset(c.getDataset())
                .lastPage(c.getLastPage())
                .totalCount(c.getTotalCount())
                .savedCount(c.getSavedCount())
                .status(CrawlCheckpoint.STATUS_RUNNING)
                .startedAt(OffsetDateTime.now())
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveCheckpoint(String dataset, int page, Integer total, int saved,
                               String status, String error) {
        CrawlCheckpoint existing = checkpointRepository.findById(dataset).orElse(null);
        checkpointRepository.save(CrawlCheckpoint.builder()
                .dataset(dataset)
                .lastPage(Math.max(page, 0))
                .totalCount(total)
                .savedCount(saved)
                .status(status)
                .lastError(truncate(error, 2000))
                .startedAt(existing == null ? OffsetDateTime.now() : existing.getStartedAt())
                .build());
    }

    // ---------- 보조 ----------

    private String keyFor(String dataset) {
        return switch (dataset) {
            case "FATALITY" -> fatalityKey;
            case "GUIDE" -> guideKey;
            case "DISASTER" -> disasterKey;
            default -> null;
        };
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String truncate(String s) {
        return truncate(s, 300);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void sleep() {
        try {
            Thread.sleep(PAGE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 테스트에서 쓰라고 열어둔 것이 아니다. 내부 레코드를 노출하지 않기 위한 조회용 */
    public Optional<CrawlCheckpoint> checkpointOf(String dataset) {
        return checkpointRepository.findById(dataset);
    }
}
