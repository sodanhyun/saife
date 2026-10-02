package io.saife.evidence.live;

import io.saife.publicapi.domain.MsdsCache;
import io.saife.publicapi.repository.MsdsCacheRepository;
import io.saife.publicapi.service.MsdsResolver;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MSDS 라이브 조회 — 목록(getChemList001)으로 chemId 확정 후 상세 4개 항목 병렬 호출.
 *
 * <p>실패·키 없음이면 {@code msds_cache}로 내려간다. 제품명 → 성분 추정은 기존 {@link MsdsResolver}를 그대로 쓴다.
 */
@Slf4j
@Service
public class MsdsLiveClient {
    public static final List<String> BRIEFING_SECTIONS = List.of("02", "05", "07", "08");
    private static final String HOST = "apis.data.go.kr/msds";

    public record MsdsBundle(String chemId, String chemNameKor, String casNo, String unNo,
                             String pictograms, Map<String, List<String>> sections) {}

    private final MsdsCacheRepository repository;
    private final MsdsResolver resolver;
    private final LiveOrCache liveOrCache;
    private final String baseUrl;
    private final String listPath;
    private final String detailPath;
    private final String key;
    /** URL → 응답 바디. 실제 배선은 JDK HttpClient, 테스트는 이 자리에 가짜 함수를 주입해 네트워크 없이 검증한다 */
    private final Function<String, String> httpGet;

    @Autowired
    public MsdsLiveClient(MsdsCacheRepository repository, MsdsResolver resolver, LiveOrCache liveOrCache,
                          @Value("${public-api.kosha.base-url}") String baseUrl,
                          @Value("${public-api.kosha.msds.list-path}") String listPath,
                          @Value("${public-api.kosha.msds.detail-path}") String detailPath,
                          @Value("${public-api.kosha.msds.key:}") String key) {
        this(repository, resolver, liveOrCache, baseUrl, listPath, detailPath, key, realHttpGet());
    }

    // 테스트 전용 생성자. httpGet을 주입받아 실제 HTTP 왕복 없이 fetchLive/store를 단위 테스트한다
    MsdsLiveClient(MsdsCacheRepository repository, MsdsResolver resolver, LiveOrCache liveOrCache,
                  String baseUrl, String listPath, String detailPath, String key,
                  Function<String, String> httpGet) {
        this.repository = repository; this.resolver = resolver; this.liveOrCache = liveOrCache;
        this.baseUrl = baseUrl; this.listPath = listPath; this.detailPath = detailPath; this.key = key;
        this.httpGet = httpGet;
    }

    private static Function<String, String> realHttpGet() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return url -> {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(7)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
                return res.body();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    public Fetched<MsdsBundle> resolve(String productName) {
        if (productName == null || productName.isBlank()) return Fetched.empty("제품명 없음");
        List<String> names = new ArrayList<>();
        names.add(productName.trim());
        names.addAll(resolver.guessIngredients(productName.trim()));
        return liveOrCache.fetch(HOST, key != null && !key.isBlank(),
                () -> fetchLive(names),
                () -> fromCache(names),
                this::store);
    }

    // ---- 라이브 ----
    private MsdsBundle fetchLive(List<String> names) throws Exception {
        for (String name : names) {
            List<MsdsXmlParser.ChemHit> hits = MsdsXmlParser.parseList(httpGet.apply(baseUrl + listPath
                    + "?serviceKey=" + enc(key) + "&searchWrd=" + enc(name) + "&searchCnd=0&numOfRows=5&pageNo=1"));
            if (hits.isEmpty()) continue;
            MsdsXmlParser.ChemHit hit = hits.get(0);

            // 4개 섹션을 병렬로 호출한다. 각 future는 자기 결과만 반환하고 공유 컬렉션에 쓰지 않는다
            // (LinkedHashMap은 버킷이 달라도 내부 연결 리스트를 공유해 동시 put()이 경합한다) —
            // join() 후 이 스레드에서만 순서대로(02→05→07→08) 조립한다.
            List<CompletableFuture<List<MsdsXmlParser.DetailItem>>> futures = new ArrayList<>();
            for (String section : BRIEFING_SECTIONS) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        String url = baseUrl + detailPath.replace("{section}", section)
                                + "?serviceKey=" + enc(key) + "&chemId=" + enc(hit.chemId());
                        return MsdsXmlParser.parseDetail(httpGet.apply(url));
                    } catch (Exception e) {
                        log.warn("[MSDS] 항목 {} 조회 실패 chemId={}: {}", section, hit.chemId(), e.getMessage());
                        return List.<MsdsXmlParser.DetailItem>of();
                    }
                }));
            }
            Map<String, List<MsdsXmlParser.DetailItem>> details = new LinkedHashMap<>();
            for (int i = 0; i < BRIEFING_SECTIONS.size(); i++) {
                details.put(BRIEFING_SECTIONS.get(i), futures.get(i).join());
            }
            if (details.values().stream().allMatch(List::isEmpty)) continue;
            Map<String, List<String>> sections = new LinkedHashMap<>();
            details.forEach((s, items) -> sections.put(s, items.stream()
                    .filter(i -> i.itemDetail() != null && !i.itemDetail().isBlank())
                    .flatMap(i -> Arrays.stream(i.itemDetail().split("\\|"))).map(String::trim)
                    .filter(x -> !x.isBlank()).toList()));
            String pictograms = MsdsXmlParser.pictogramsOf(details.getOrDefault("02", List.of()));
            return new MsdsBundle(hit.chemId(), hit.chemNameKor(), hit.casNo(), hit.unNo(), pictograms, sections);
        }
        return null;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // ---- 캐시 ----
    private Optional<MsdsBundle> fromCache(List<String> names) {
        for (String name : names) {
            String chemId = resolver.resolveChemId(name);
            if (chemId == null) continue;
            List<MsdsCache> rows = repository.findByChemId(chemId);
            if (rows.isEmpty()) continue;
            Map<String, List<String>> sections = new LinkedHashMap<>();
            for (MsdsCache r : rows) {
                if (r.getItemDetail() == null) continue;
                sections.computeIfAbsent(r.getSectionCode(), k -> new ArrayList<>())
                        .addAll(Arrays.stream(r.getItemDetail().split("\\|")).map(String::trim).filter(x -> !x.isBlank()).toList());
            }
            MsdsCache first = rows.get(0);
            String pictograms = rows.stream().map(MsdsCache::getPictograms).filter(Objects::nonNull).findFirst().orElse(null);
            return Optional.of(new MsdsBundle(chemId, first.getChemNameKor(), first.getCasNo(), first.getUnNo(), pictograms, sections));
        }
        return Optional.empty();
    }

    // LiveOrCache가 이 메서드를 람다(this::store)로 호출한다. 프록시를 거치지 않는 자기 참조 호출이라
    // @Transactional을 붙여도 AOP가 적용되지 않으므로 애초에 붙이지 않는다.
    // 대신 repository.save() 각 호출이 자기 트랜잭션으로 개별 커밋되는 것을 그대로 받아들인다.
    public void store(MsdsBundle b) {
        // 같은 chemId·섹션은 지우고 다시 넣는다 (UNIQUE(chem_id, section_code, item_code))
        List<MsdsCache> old = repository.findByChemId(b.chemId());
        repository.deleteAll(old);
        b.sections().forEach((section, lines) -> {
            if (lines.isEmpty()) return;
            repository.save(MsdsCache.builder()
                    .chemId(b.chemId()).chemNameKor(b.chemNameKor()).casNo(b.casNo()).unNo(b.unNo())
                    .sectionCode(section).itemCode("LIVE-" + section)
                    .itemName(sectionName(section)).itemDetail(String.join("|", lines))
                    .pictograms(b.pictograms()).fetchedAt(OffsetDateTime.now()).build());
        });
    }

    private static String sectionName(String s) {
        return switch (s) {
            case "02" -> "유해성, 위험성"; case "05" -> "폭발, 화재 시 대처방법";
            case "07" -> "취급 및 저장방법"; case "08" -> "노출방지 및 개인보호구";
            default -> "항목 " + s;
        };
    }
}
