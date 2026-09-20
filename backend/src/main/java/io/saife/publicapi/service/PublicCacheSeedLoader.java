package io.saife.publicapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.core.domain.AccidentType;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 동봉된 공공 데이터 캐시를 적재한다 — <b>제1원칙: 심사위원은 키가 없다.</b>
 *
 * <p>{@code git clone} 후 API 키 하나 없이 앱을 띄웠을 때도 사례 검색이 돌아가야 한다.
 * 그래서 수집 결과 9,312건을 {@code resources/seed/}에 gzip JSONL로 동봉하고,
 * 테이블이 비어 있으면 기동 시 넣는다.
 *
 * <p><b>테이블이 비어 있을 때만</b> 동작한다. 이미 데이터가 있으면 건드리지 않는다 —
 * 크롤러로 갱신한 최신 데이터를 기동할 때마다 되돌리면 안 된다.
 *
 * <p>Flyway 마이그레이션이 아니라 러너로 넣는 이유는 2MB짜리 INSERT 스크립트가
 * 마이그레이션 체크섬에 묶이면 데이터를 갱신할 때마다 새 버전을 찍어야 하기 때문이다.
 * 캐시는 스키마가 아니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PublicCacheSeedLoader implements ApplicationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CASE_RESOURCE = "seed/public_case.jsonl.gz";
    private static final String GUIDE_RESOURCE = "seed/kosha_guide.jsonl.gz";
    private static final int BATCH = 500;

    private final PublicCaseRepository publicCaseRepository;
    private final KoshaGuideRepository koshaGuideRepository;

    @Override
    public void run(ApplicationArguments args) {
        loadCases();
        loadGuides();
    }

    private void loadCases() {
        if (publicCaseRepository.count() > 0) {
            log.debug("[SEED] public_case에 이미 데이터가 있어 동봉 캐시를 넣지 않는다");
            return;
        }
        List<PublicCase> buffer = new ArrayList<>(BATCH);
        int total = 0;

        try (BufferedReader reader = open(CASE_RESOURCE)) {
            if (reader == null) {
                log.warn("[SEED] {} 없음 — 사례 검색이 비어 있는 상태로 뜬다", CASE_RESOURCE);
                return;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = MAPPER.readTree(line);
                buffer.add(PublicCase.builder()
                        .source(text(n, "source"))
                        .sourceKey(text(n, "sourceKey"))
                        .business(text(n, "business"))
                        .keyword(text(n, "keyword"))
                        .contents(text(n, "contents"))
                        .accidentType(axis(text(n, "accidentType")))
                        .region(text(n, "region"))
                        .occurredOn(date(text(n, "occurredOn")))
                        .fetchedAt(OffsetDateTime.now())
                        .build());

                if (buffer.size() >= BATCH) {
                    total += flushCases(buffer);
                }
            }
            total += flushCases(buffer);
            log.info("[SEED] 동봉 공공 사례 {}건 적재", total);

        } catch (Exception e) {
            // 캐시 적재 실패로 앱이 죽으면 안 된다. 사례 검색만 비는 것이 낫다
            log.error("[SEED] 공공 사례 적재 실패 — 사례 검색이 비어 있는 상태로 진행한다", e);
        }
    }

    private void loadGuides() {
        if (koshaGuideRepository.count() > 0) {
            return;
        }
        List<KoshaGuide> buffer = new ArrayList<>(BATCH);
        int total = 0;

        try (BufferedReader reader = open(GUIDE_RESOURCE)) {
            if (reader == null) {
                return;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = MAPPER.readTree(line);
                buffer.add(KoshaGuide.builder()
                        .guideNo(text(n, "guideNo"))
                        .guideName(text(n, "guideName"))
                        .announcedOn(date(text(n, "announcedOn")))
                        .fileDownloadUrl(text(n, "fileDownloadUrl"))
                        .fetchedAt(OffsetDateTime.now())
                        .build());

                if (buffer.size() >= BATCH) {
                    total += flushGuides(buffer);
                }
            }
            total += flushGuides(buffer);
            log.info("[SEED] 동봉 KOSHA GUIDE {}건 적재", total);

        } catch (Exception e) {
            log.error("[SEED] KOSHA GUIDE 적재 실패", e);
        }
    }

    @Transactional
    public int flushCases(List<PublicCase> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        int n = buffer.size();
        publicCaseRepository.saveAll(buffer);
        buffer.clear();
        return n;
    }

    @Transactional
    public int flushGuides(List<KoshaGuide> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        int n = buffer.size();
        koshaGuideRepository.saveAll(buffer);
        buffer.clear();
        return n;
    }

    private BufferedReader open(String resource) throws Exception {
        ClassPathResource cp = new ClassPathResource(resource);
        if (!cp.exists()) {
            return null;
        }
        InputStream in = new GZIPInputStream(cp.getInputStream());
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private AccidentType axis(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AccidentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
