# 근거 계층 A4 — 인덱스 빌더·시드 동봉·미디어 프록시·시스템 상태 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 청크를 만들어 임베딩해 `evidence_chunk`에 넣고(`IndexBuilder`), 그것을 시드 파일로 내보내고(`SeedExporter`) 기동 시 되읽으며(`PublicCacheSeedLoader` 확장), 사진·PDF를 온디맨드로 프록시·캐시하고(`MediaController`), 프론트가 읽을 시스템 상태를 낸다.

**Architecture:** `io.saife.evidence.index`(빌더·시드), `io.saife.evidence.media`(프록시), `io.saife.evidence.SystemStatusController`. 임베딩은 100건 배치, 체크포인트는 기존 `crawl_checkpoint` 테이블 재사용(dataset=`INDEX_CASE` 등). 시드 벡터는 float32 LE base64.

**Tech Stack:** Spring AI `EmbeddingModel` · JdbcTemplate · Jackson · `javax.imageio` · JDK `HttpClient`.

**Spec:** §4(시드), §6.4, §6.5, §6.6 · 선행: A1, A2, A3

## Global Constraints

- A1 Global Constraints 적용.
- `IndexBuilder`는 `searchable=false`(parent) 행을 **임베딩하지 않는다**.
- 미디어 프록시는 URL을 파라미터로 받지 않는다. DB의 id/guideNo로만 원본을 찾고 허용 호스트는 `portal.kosha.or.kr`뿐이다.
- 시드 파일 경로: `backend/src/main/resources/seed/{public_case,kosha_guide,law_article,evidence_chunk,msds_cache}.jsonl.gz`. 시드 로더는 테이블이 비어 있을 때만 적재한다.
- 디스크 캐시 루트 `${saife.media-dir}`(기본 `./data/media`, gitignore).

## Review Focus

1. 시드 벡터 base64 라운드트립 — float32 LE 768개가 인코딩·디코딩 후 동일해야 한다. → Task 2 `VectorCodecTest.라운드트립`.
2. child의 `parentRefKey`가 가리키는 parent가 시드 순서상 뒤에 있어도 적재가 되어야 한다(parent 먼저 정렬). → Task 3 `EvidenceSeedLoaderIT.parent가_뒤에_있어도_연결`.
3. 미디어 프록시가 DB에 없는 id를 받으면 404, 원본이 404이면 404를 그대로(빈 파일을 캐시하지 않는다). → Task 4 `MediaControllerTest.원본_실패는_캐시하지_않는다`.
4. `?w=320` 썸네일 요청이 PNG가 아닌 JPEG를 돌려주고 두 번째 요청은 디스크에서 온다. → Task 4 `MediaCacheTest.썸네일_생성과_재사용`.
5. 인덱스 빌드 중 임베딩 호출이 실패하면 체크포인트가 마지막 성공 배치에 남고 다음 실행이 거기서 이어받는다. → Task 1 `IndexBuilderTest.실패_시_체크포인트_유지`.

---

### Task 1: `IndexBuilder` — kind별 청크 생성·임베딩·적재(체크포인트)

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/index/IndexBuilder.java`
- Create: `backend/src/main/java/io/saife/evidence/index/BatchEmbedder.java`
- Modify: `backend/src/main/java/io/saife/evidence/EvidenceAdminController.java` (엔드포인트 추가)
- Modify: `backend/src/main/java/io/saife/publicapi/service/PublicApiCrawler.java` (GUIDE 저장 후 PDF 텍스트 훅은 Task 5)
- Test: `backend/src/test/java/io/saife/evidence/index/IndexBuilderTest.java`

**Interfaces:**
- Produces:
  - `interface BatchEmbedder { List<float[]> embedAll(List<String> texts); }` + `GeminiBatchEmbedder`(100건/호출, `EmbeddingModel.embed(List<String>)`)
  - `IndexBuilder.rebuild(EvidenceKind kind) → IndexReport(kind, chunks, embedded, skipped, status, message)` — CASE_*는 `PublicCase` 전체, LAW는 `law_article` 조 단위 그룹, GUIDE는 `GuideTextSource.pagesOf(guide)`(Task 5)를 통해
  - `POST /api/admin/index/rebuild?kind=CASE|GUIDE|LAW|ALL`

- [ ] **Step 1: 실패하는 테스트 (리포지토리·임베더 가짜)**

```java
package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.repository.LawArticleRepository;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.publicapi.domain.CrawlCheckpoint;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.CrawlCheckpointRepository;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class IndexBuilderTest {
    private final EvidenceChunkRepository chunks = mock(EvidenceChunkRepository.class);
    private final PublicCaseRepository cases = mock(PublicCaseRepository.class);
    private final KoshaGuideRepository guides = mock(KoshaGuideRepository.class);
    private final LawArticleRepository laws = mock(LawArticleRepository.class);
    private final CrawlCheckpointRepository checkpoints = mock(CrawlCheckpointRepository.class);
    private final GuideTextSource guideText = mock(GuideTextSource.class);
    private final io.saife.evidence.chunk.ContextualEnricher enricher = mock(io.saife.evidence.chunk.ContextualEnricher.class);

    private List<PublicCase> cases(int n) {
        return LongStream.rangeClosed(1, n).mapToObj(i -> PublicCase.builder().id(i).source("FATALITY").sourceKey("K" + i)
                .keyword("k" + i).contents("c" + i).accidentType(AccidentType.FALL).build()).toList();
    }

    @Test
    void 사례를_100건씩_임베딩해_넣는다() {
        when(cases.findAll()).thenReturn(cases(250));
        when(checkpoints.findById("INDEX_CASE_FATALITY")).thenReturn(Optional.empty());
        BatchEmbedder embedder = texts -> texts.stream().map(t -> new float[]{1f}).toList();
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> { List<?> l = inv.getArgument(0); return l.stream().map(x -> 1L).toList(); });
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder);
        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.CASE_FATALITY);
        assertThat(r.embedded()).isEqualTo(250);
        verify(chunks, times(3)).insertBatch(anyList());   // 100 + 100 + 50
        verify(checkpoints, atLeast(3)).save(any(CrawlCheckpoint.class));
    }

    @Test
    void 실패_시_체크포인트_유지() {
        when(cases.findAll()).thenReturn(cases(250));
        when(checkpoints.findById("INDEX_CASE_FATALITY")).thenReturn(Optional.empty());
        int[] call = {0};
        BatchEmbedder embedder = texts -> { if (++call[0] == 2) throw new RuntimeException("429"); return texts.stream().map(t -> new float[]{1f}).toList(); };
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> { List<?> l = inv.getArgument(0); return l.stream().map(x -> 1L).toList(); });
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder);
        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.CASE_FATALITY);
        assertThat(r.status()).isEqualTo(CrawlCheckpoint.STATUS_FAILED);
        assertThat(r.embedded()).isEqualTo(100);
        // 재실행은 lastPage=1 뒤부터
        when(checkpoints.findById("INDEX_CASE_FATALITY")).thenReturn(Optional.of(CrawlCheckpoint.builder()
                .dataset("INDEX_CASE_FATALITY").lastPage(1).savedCount(100).status(CrawlCheckpoint.STATUS_FAILED).build()));
        call[0] = 10;
        IndexBuilder.IndexReport r2 = b.rebuild(EvidenceKind.CASE_FATALITY);
        assertThat(r2.embedded()).isEqualTo(150);
        assertThat(r2.status()).isEqualTo(CrawlCheckpoint.STATUS_DONE);
    }

    @Test
    void parent는_임베딩하지_않는다() {
        io.saife.evidence.domain.LawArticle p1 = io.saife.evidence.domain.LawArticle.builder().id(1L).lawId("L").lawName("법").articleNo(1).articleSub(0).paragraphNo(1).text("①").build();
        io.saife.evidence.domain.LawArticle p2 = io.saife.evidence.domain.LawArticle.builder().id(2L).lawId("L").lawName("법").articleNo(1).articleSub(0).paragraphNo(2).text("②").build();
        when(laws.findAll()).thenReturn(List.of(p1, p2));
        when(checkpoints.findById("INDEX_LAW")).thenReturn(Optional.empty());
        List<Integer> embedCounts = new ArrayList<>();
        BatchEmbedder embedder = texts -> { embedCounts.add(texts.size()); return texts.stream().map(t -> new float[]{1f}).toList(); };
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> { List<?> l = inv.getArgument(0); return LongStream.rangeClosed(1, l.size()).boxed().toList(); });
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder);
        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.LAW);
        assertThat(r.chunks()).isEqualTo(3);      // parent 1 + child 2
        assertThat(r.embedded()).isEqualTo(2);    // child만
        assertThat(embedCounts).containsExactly(2);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.index.IndexBuilderTest`
Expected: FAIL

- [ ] **Step 3: 구현**

`BatchEmbedder.java`:
```java
package io.saife.evidence.index;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/** 배치 임베딩. 테스트에서 람다로 대체한다 */
public interface BatchEmbedder {
    List<float[]> embedAll(List<String> texts);

    @Component
    @RequiredArgsConstructor
    class Gemini implements BatchEmbedder {
        private final EmbeddingModel model;
        @Override public List<float[]> embedAll(List<String> texts) {
            return new ArrayList<>(model.embed(texts));
        }
    }
}
```

`GuideTextSource.java`(인터페이스만 여기, 구현은 Task 5):
```java
package io.saife.evidence.index;

import io.saife.publicapi.domain.KoshaGuide;
import java.util.List;

/** 지침 PDF 페이지 텍스트 공급. 다운로드+PDFBox. 실패면 빈 리스트 */
public interface GuideTextSource {
    List<String> pagesOf(KoshaGuide guide);
}
```

`IndexBuilder.java`:
```java
package io.saife.evidence.index;

import io.saife.evidence.EvidenceKind;
import io.saife.evidence.chunk.*;
import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.repository.LawArticleRepository;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.evidence.search.EvidenceChunkRepository.ChunkRow;
import io.saife.publicapi.domain.CrawlCheckpoint;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.CrawlCheckpointRepository;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * kind별로 청크를 만들어 임베딩하고 evidence_chunk에 넣는다. 100건 배치, 배치마다 체크포인트.
 * parent(searchable=false)는 텍스트만 넣고 임베딩하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexBuilder {
    static final int BATCH = 100;

    public record IndexReport(EvidenceKind kind, int chunks, int embedded, int skipped, String status, String message) {}

    private final EvidenceChunkRepository chunks;
    private final PublicCaseRepository cases;
    private final KoshaGuideRepository guides;
    private final LawArticleRepository laws;
    private final CrawlCheckpointRepository checkpoints;
    private final GuideTextSource guideText;
    private final ContextualEnricher enricher;
    private final BatchEmbedder embedder;

    public IndexReport rebuild(EvidenceKind kind) {
        String ds = "INDEX_" + kind.name();
        CrawlCheckpoint cp = checkpoints.findById(ds).orElse(null);
        int startBatch = cp == null || CrawlCheckpoint.STATUS_DONE.equals(cp.getStatus()) ? 0 : cp.getLastPage();
        List<ChunkDraft> drafts = draftsOf(kind);
        int total = drafts.size();
        // parent 먼저 넣고 refKey→id 맵을 만든다 (임베딩 없음)
        Map<String, Long> parentIds = new HashMap<>();
        List<ChunkRow> parentRows = drafts.stream().filter(d -> !d.searchable()).map(d -> row(d, null, null)).toList();
        if (!parentRows.isEmpty()) {
            List<Long> ids = chunks.insertBatch(parentRows);
            for (int i = 0; i < ids.size(); i++) parentIds.put(parentRows.get(i).refKey(), ids.get(i));
        }
        List<ChunkDraft> children = drafts.stream().filter(ChunkDraft::searchable).toList();
        int embedded = startBatch * BATCH;
        int batchNo = startBatch;
        try {
            for (int from = startBatch * BATCH; from < children.size(); from += BATCH) {
                List<ChunkDraft> slice = children.subList(from, Math.min(children.size(), from + BATCH));
                List<float[]> vectors = embedder.embedAll(slice.stream().map(ChunkDraft::text).toList());
                List<ChunkRow> rows = new ArrayList<>();
                for (int i = 0; i < slice.size(); i++) {
                    ChunkDraft d = slice.get(i);
                    rows.add(row(d, d.parentRefKey() == null ? null : parentIds.get(d.parentRefKey()), vectors.get(i)));
                }
                chunks.insertBatch(rows);
                embedded = Math.min(children.size(), from + BATCH);
                batchNo++;
                save(ds, batchNo, embedded, CrawlCheckpoint.STATUS_RUNNING, null);
                log.info("[INDEX] {} 배치 {} — {}/{}", kind, batchNo, embedded, children.size());
            }
            save(ds, batchNo, embedded, CrawlCheckpoint.STATUS_DONE, null);
            return new IndexReport(kind, total, embedded, total - children.size(), CrawlCheckpoint.STATUS_DONE, "완료");
        } catch (Exception e) {
            save(ds, batchNo, embedded, CrawlCheckpoint.STATUS_FAILED, e.toString());
            log.error("[INDEX] {} 중단 배치 {} — 다시 실행하면 이어받는다", kind, batchNo, e);
            return new IndexReport(kind, total, embedded, total - children.size(), CrawlCheckpoint.STATUS_FAILED, "중단: " + e.getMessage());
        }
    }

    private List<ChunkDraft> draftsOf(EvidenceKind kind) {
        return switch (kind) {
            case CASE_FATALITY, CASE_DISASTER -> {
                String source = kind == EvidenceKind.CASE_FATALITY ? "FATALITY" : "DISASTER";
                yield cases.findAll().stream().filter(c -> source.equals(c.getSource())).map(CaseChunkBuilder::build).toList();
            }
            case LAW -> {
                Map<String, List<LawArticle>> byArticle = new LinkedHashMap<>();
                for (LawArticle a : laws.findAll()) byArticle.computeIfAbsent(a.getLawId() + ":" + a.getArticleNo() + ":" + a.getArticleSub(), k -> new ArrayList<>()).add(a);
                List<ChunkDraft> out = new ArrayList<>();
                byArticle.values().forEach(g -> out.addAll(LawChunkBuilder.build(g)));
                yield out;
            }
            case GUIDE -> {
                List<ChunkDraft> out = new ArrayList<>();
                for (KoshaGuide g : guides.findAll()) {
                    List<String> pages = guideText.pagesOf(g);
                    if (pages.isEmpty()) continue;
                    List<ChunkDraft> gd = GuideChunkBuilder.build(g.getId(), g.getGuideNo(), g.getGuideName(), g.getAnnouncedOn(), pages);
                    String full = String.join("\n", pages);
                    int offset = 0;
                    for (ChunkDraft d : gd) {
                        if (!d.searchable()) { out.add(d); continue; }
                        String enriched = enricher.enrich(full, d.text(), Math.max(0, full.indexOf(d.text().substring(0, Math.min(40, d.text().length())))), g.getGuideName());
                        out.add(d.withText(enriched));
                        offset += d.text().length();
                    }
                }
                yield out;
            }
            case MSDS -> List.of();
        };
    }

    private ChunkRow row(ChunkDraft d, Long parentId, float[] v) {
        Map<String, Object> meta = new LinkedHashMap<>(d.metadata());
        return new ChunkRow(d.kind(), d.refId(), d.refKey(), d.chunkLevel(), parentId, d.searchable(), d.sectionTitle(), d.title(), d.text(), meta, v);
    }

    private void save(String ds, int batch, int saved, String status, String error) {
        CrawlCheckpoint existing = checkpoints.findById(ds).orElse(null);
        checkpoints.save(CrawlCheckpoint.builder().dataset(ds).lastPage(batch).savedCount(saved).status(status)
                .lastError(error == null ? null : error.substring(0, Math.min(2000, error.length())))
                .startedAt(existing == null ? OffsetDateTime.now() : existing.getStartedAt()).build());
    }
}
```
`EvidenceAdminController`에 추가:
```java
    private final IndexBuilder indexBuilder;

    /** kind=CASE_FATALITY|CASE_DISASTER|GUIDE|LAW|ALL. 키 필요(임베딩). 재실행은 체크포인트부터 */
    @PostMapping("/index/rebuild")
    public ResponseEntity<List<IndexBuilder.IndexReport>> rebuild(@RequestParam(defaultValue = "ALL") String kind) {
        List<EvidenceKind> kinds = "ALL".equalsIgnoreCase(kind)
                ? List.of(EvidenceKind.CASE_FATALITY, EvidenceKind.CASE_DISASTER, EvidenceKind.LAW, EvidenceKind.GUIDE)
                : List.of(EvidenceKind.valueOf(kind.toUpperCase()));
        return ResponseEntity.ok(kinds.stream().map(indexBuilder::rebuild).toList());
    }
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.index.IndexBuilderTest`
Expected: PASS 3건

```bash
git add backend/src/main/java/io/saife/evidence
git add backend/src/test/java/io/saife/evidence/index
git commit -m "feat(evidence): IndexBuilder — kind별 청크 생성·100건 배치 임베딩·체크포인트 재개, parent 미임베딩"
```

---

### Task 2: `VectorCodec` + `SeedExporter`

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/index/VectorCodec.java`
- Create: `backend/src/main/java/io/saife/evidence/index/SeedExporter.java`
- Modify: `EvidenceAdminController` (`GET /api/admin/index/export`)
- Test: `backend/src/test/java/io/saife/evidence/index/VectorCodecTest.java`

**Interfaces:**
- Produces: `VectorCodec.encode(float[]) → String`(base64 float32 LE), `VectorCodec.decode(String) → float[]`; `SeedExporter.exportAll(Path dir) → Map<String,Integer>`(파일명→행 수)

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class VectorCodecTest {
    @Test
    void 라운드트립() {
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) v[i] = (float) Math.sin(i) * 0.01f;
        assertThat(VectorCodec.decode(VectorCodec.encode(v))).containsExactly(v);
        assertThat(VectorCodec.encode(v).length()).isEqualTo(4096);   // 768*4 bytes → base64 4096자
    }

    @Test
    void null과_빈문자열() {
        assertThat(VectorCodec.encode(null)).isNull();
        assertThat(VectorCodec.decode(null)).isNull();
        assertThat(VectorCodec.decode("")).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`VectorCodec.java`:
```java
package io.saife.evidence.index;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/** float32 little-endian base64. JSON에 벡터를 숫자 배열로 쓰면 3배 커진다 */
public final class VectorCodec {
    private VectorCodec() {}
    public static String encode(float[] v) {
        if (v == null) return null;
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return Base64.getEncoder().encodeToString(b.array());
    }
    public static float[] decode(String s) {
        if (s == null || s.isEmpty()) return null;
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.remaining() / 4];
        for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
        return v;
    }
}
```

`SeedExporter.java`:
```java
package io.saife.evidence.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * DB → seed/*.jsonl.gz. 개발자가 결과를 backend/src/main/resources/seed/로 복사해 커밋한다.
 * evidence_chunk는 parent가 먼저 오도록 정렬해 쓴다(로더가 순서대로 넣는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeedExporter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Map<String, Integer> exportAll(Path dir) throws IOException {
        Files.createDirectories(dir);
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("public_case.jsonl.gz", dump(dir.resolve("public_case.jsonl.gz"),
                "select source, source_key as \"sourceKey\", business, keyword, contents, accident_type as \"accidentType\", region, occurred_on::text as \"occurredOn\", image_url as \"imageUrl\", source_url as \"sourceUrl\" from public_case order by id"));
        out.put("kosha_guide.jsonl.gz", dump(dir.resolve("kosha_guide.jsonl.gz"),
                "select guide_no as \"guideNo\", guide_name as \"guideName\", announced_on::text as \"announcedOn\", file_download_url as \"fileDownloadUrl\" from kosha_guide order by id"));
        out.put("law_article.jsonl.gz", dump(dir.resolve("law_article.jsonl.gz"),
                "select law_id as \"lawId\", law_name as \"lawName\", article_no as \"articleNo\", article_sub as \"articleSub\", paragraph_no as \"paragraphNo\", title, text, effective_on::text as \"effectiveOn\", source_url as \"sourceUrl\" from law_article order by law_id, article_no, article_sub, paragraph_no"));
        out.put("msds_cache.jsonl.gz", dump(dir.resolve("msds_cache.jsonl.gz"),
                "select chem_id as \"chemId\", chem_name_kor as \"chemNameKor\", cas_no as \"casNo\", un_no as \"unNo\", section_code as \"sectionCode\", item_code as \"itemCode\", item_name as \"itemName\", item_detail as \"itemDetail\", pictograms from msds_cache order by id"));
        out.put("evidence_chunk.jsonl.gz", dumpChunks(dir.resolve("evidence_chunk.jsonl.gz")));
        return out;
    }

    private int dump(Path file, String sql) throws IOException {
        int[] n = {0};
        try (Writer w = writer(file)) {
            jdbc.query(sql, rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                var md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                try { w.write(mapper.writeValueAsString(row)); w.write('\n'); n[0]++; } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
        return n[0];
    }

    private int dumpChunks(Path file) throws IOException {
        int[] n = {0};
        try (Writer w = writer(file)) {
            jdbc.query("""
                    select c.kind, c.ref_key as "refKey", c.chunk_level as "chunkLevel", p.ref_key as "parentRefKey", c.searchable,
                           c.section_title as "sectionTitle", c.title, c.text, c.metadata::text as metadata, c.embedding::text as embedding
                    from evidence_chunk c left join evidence_chunk p on p.id = c.parent_id
                    order by (c.chunk_level = 'parent') desc, c.id
                    """, rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", rs.getString("kind")); row.put("refKey", rs.getString("refKey"));
                row.put("chunkLevel", rs.getString("chunkLevel")); row.put("parentRefKey", rs.getString("parentRefKey"));
                row.put("searchable", rs.getBoolean("searchable")); row.put("sectionTitle", rs.getString("sectionTitle"));
                row.put("title", rs.getString("title")); row.put("text", rs.getString("text"));
                try { row.put("metadata", mapper.readTree(rs.getString("metadata"))); } catch (Exception e) { row.put("metadata", Map.of()); }
                String emb = rs.getString("embedding");
                row.put("embedding", emb == null ? null : VectorCodec.encode(parseVector(emb)));
                try { w.write(mapper.writeValueAsString(row)); w.write('\n'); n[0]++; } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
        return n[0];
    }

    static float[] parseVector(String literal) {
        String[] parts = literal.substring(1, literal.length() - 1).split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = Float.parseFloat(parts[i].trim());
        return v;
    }

    private Writer writer(Path file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8));
    }
}
```
`EvidenceAdminController`에:
```java
    private final SeedExporter seedExporter;

    /** ./data/export/*.jsonl.gz 생성. seed/로 복사해 커밋한다 */
    @GetMapping("/index/export")
    public ResponseEntity<Map<String, Integer>> export() throws java.io.IOException {
        return ResponseEntity.ok(seedExporter.exportAll(java.nio.file.Path.of("./data/export")));
    }
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.index.VectorCodecTest`
Expected: PASS 2건

```bash
git add backend/src/main/java/io/saife/evidence backend/src/test/java/io/saife/evidence/index
git commit -m "feat(evidence): 시드 내보내기 — 5개 jsonl.gz, 벡터는 float32 base64, parent 우선 정렬"
```

---

### Task 3: 시드 로더 확장 — `law_article`·`msds_cache`·`evidence_chunk` 적재

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/index/EvidenceSeedLoader.java`
- Modify: `backend/src/main/java/io/saife/publicapi/service/PublicCacheSeedLoader.java` (`run`에서 `evidenceSeedLoader.load()` 호출, `@Order`)
- Test: `backend/src/test/java/io/saife/evidence/index/EvidenceSeedLoaderIT.java`

**Interfaces:**
- Produces: `EvidenceSeedLoader.load()`(각 테이블이 비었을 때만), `EvidenceSeedLoader.loadChunksFrom(BufferedReader) → int`(테스트용)

- [ ] **Step 1: 실패하는 통합 테스트**

```java
package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.search.EvidenceChunkRepository;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EvidenceSeedLoaderIT {
    @Autowired EvidenceSeedLoader loader;
    @Autowired EvidenceChunkRepository repo;

    @Test
    void parent가_뒤에_있어도_연결() {
        float[] v = new float[768]; v[0] = 1f;
        String child = "{\"kind\":\"GUIDE\",\"refKey\":\"SEED#c1\",\"chunkLevel\":\"child\",\"parentRefKey\":\"SEED#p1\",\"searchable\":true,\"sectionTitle\":\"s\",\"title\":\"t\",\"text\":\"child\",\"metadata\":{\"guideNo\":\"SEED\"},\"embedding\":\"" + VectorCodec.encode(v) + "\"}";
        String parent = "{\"kind\":\"GUIDE\",\"refKey\":\"SEED#p1\",\"chunkLevel\":\"parent\",\"parentRefKey\":null,\"searchable\":false,\"sectionTitle\":\"s\",\"title\":\"T\",\"text\":\"parent\",\"metadata\":{},\"embedding\":null}";
        int n = loader.loadChunksFrom(new BufferedReader(new StringReader(child + "\n" + parent + "\n")));
        assertThat(n).isEqualTo(2);
        Long pid = repo.findIdByRefKey(io.saife.evidence.EvidenceKind.GUIDE, "SEED#p1").orElseThrow();
        var hits = repo.vectorSearch(v, Set.of(io.saife.evidence.EvidenceKind.GUIDE), null, 5);
        assertThat(hits).anySatisfy(h -> { assertThat(h.refKey()).isEqualTo("SEED#c1"); assertThat(h.parentId()).isEqualTo(pid); });
    }
}
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

```java
package io.saife.evidence.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.repository.LawArticleRepository;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.evidence.search.EvidenceChunkRepository.ChunkRow;
import io.saife.publicapi.domain.MsdsCache;
import io.saife.publicapi.repository.MsdsCacheRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 동봉 시드 → law_article·msds_cache·evidence_chunk. 테이블이 비어 있을 때만.
 * 청크는 두 패스: ① parent 전부 ② child(parentRefKey → id). 시드가 parent 우선 정렬이라 한 패스로도 되지만 순서를 믿지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceSeedLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BATCH = 500;
    private final LawArticleRepository laws;
    private final MsdsCacheRepository msds;
    private final EvidenceChunkRepository chunks;

    public void load() {
        try { if (laws.count() == 0) log.info("[SEED] 조문 {}건 적재", loadLaws()); } catch (Exception e) { log.error("[SEED] 조문 적재 실패", e); }
        try { if (msds.count() <= 9) log.info("[SEED] MSDS {}건 적재", loadMsds()); } catch (Exception e) { log.error("[SEED] MSDS 적재 실패", e); }
        try {
            if (chunks.countByKind().isEmpty()) {
                try (BufferedReader r = open("seed/evidence_chunk.jsonl.gz")) { if (r != null) log.info("[SEED] 근거 청크 {}건 적재", loadChunksFrom(r)); }
            }
        } catch (Exception e) { log.error("[SEED] 근거 청크 적재 실패 — 검색이 비어 있는 상태로 뜬다", e); }
    }

    int loadLaws() throws IOException {
        int n = 0;
        try (BufferedReader r = open("seed/law_article.jsonl.gz")) {
            if (r == null) return 0;
            List<LawArticle> buf = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode j = MAPPER.readTree(line);
                buf.add(LawArticle.builder().lawId(j.path("lawId").asText()).lawName(j.path("lawName").asText())
                        .articleNo(j.path("articleNo").asInt()).articleSub(j.path("articleSub").asInt(0)).paragraphNo(j.path("paragraphNo").asInt(0))
                        .title(text(j, "title")).text(j.path("text").asText()).effectiveOn(date(text(j, "effectiveOn")))
                        .sourceUrl(text(j, "sourceUrl")).fetchedAt(OffsetDateTime.now()).build());
                if (buf.size() >= BATCH) { laws.saveAll(buf); n += buf.size(); buf.clear(); }
            }
            laws.saveAll(buf); n += buf.size();
        }
        return n;
    }

    int loadMsds() throws IOException {
        int n = 0;
        try (BufferedReader r = open("seed/msds_cache.jsonl.gz")) {
            if (r == null) return 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode j = MAPPER.readTree(line);
                msds.save(MsdsCache.builder().chemId(j.path("chemId").asText()).chemNameKor(text(j, "chemNameKor")).casNo(text(j, "casNo"))
                        .unNo(text(j, "unNo")).sectionCode(j.path("sectionCode").asText()).itemCode(text(j, "itemCode")).itemName(text(j, "itemName"))
                        .itemDetail(text(j, "itemDetail")).pictograms(text(j, "pictograms")).fetchedAt(OffsetDateTime.now()).build());
                n++;
            }
        }
        return n;
    }

    public int loadChunksFrom(BufferedReader r) throws IOException {
        List<JsonNode> children = new ArrayList<>();
        Map<String, Long> parentIds = new HashMap<>();
        List<ChunkRow> buf = new ArrayList<>();
        List<String> bufKeys = new ArrayList<>();
        int n = 0;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode j = MAPPER.readTree(line);
            if (!j.path("searchable").asBoolean(true)) {
                buf.add(row(j, null)); bufKeys.add(j.path("refKey").asText());
                if (buf.size() >= BATCH) { n += flushParents(buf, bufKeys, parentIds); }
            } else children.add(j);
        }
        n += flushParents(buf, bufKeys, parentIds);
        for (JsonNode j : children) {
            String pk = text(j, "parentRefKey");
            Long pid = pk == null ? null : parentIds.get(pk);
            if (pk != null && pid == null) pid = chunks.findIdByRefKey(EvidenceKind.valueOf(j.path("kind").asText()), pk).orElse(null);
            buf.add(row(j, pid));
            if (buf.size() >= BATCH) { chunks.insertBatch(buf); n += buf.size(); buf.clear(); }
        }
        if (!buf.isEmpty()) { chunks.insertBatch(buf); n += buf.size(); buf.clear(); }
        return n;
    }

    private int flushParents(List<ChunkRow> buf, List<String> keys, Map<String, Long> parentIds) {
        if (buf.isEmpty()) return 0;
        List<Long> ids = chunks.insertBatch(buf);
        for (int i = 0; i < ids.size(); i++) parentIds.put(keys.get(i), ids.get(i));
        int n = buf.size(); buf.clear(); keys.clear();
        return n;
    }

    private ChunkRow row(JsonNode j, Long parentId) throws IOException {
        Map<String, Object> meta = MAPPER.convertValue(j.path("metadata"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        return new ChunkRow(EvidenceKind.valueOf(j.path("kind").asText()), 0L, j.path("refKey").asText(), j.path("chunkLevel").asText("child"),
                parentId, j.path("searchable").asBoolean(true), text(j, "sectionTitle"), j.path("title").asText(), j.path("text").asText(),
                meta == null ? Map.of() : meta, VectorCodec.decode(text(j, "embedding")));
    }

    private BufferedReader open(String resource) throws IOException {
        ClassPathResource cp = new ClassPathResource(resource);
        if (!cp.exists()) { log.warn("[SEED] {} 없음", resource); return null; }
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(cp.getInputStream()), StandardCharsets.UTF_8));
    }

    private static String text(JsonNode n, String f) { JsonNode v = n.path(f); return v.isMissingNode() || v.isNull() ? null : v.asText(); }
    private static LocalDate date(String s) { try { return s == null ? null : LocalDate.parse(s); } catch (Exception e) { return null; } }
}
```
`ChunkRow.refId`가 0인 문제: 시드에는 ref_id가 없다(재적재 시 public_case id가 달라진다). 로더에서 CASE는 `source:sourceKey`로, GUIDE는 `guideNo`(refKey의 `#` 앞)로, LAW는 `lawId:조:sub:항`으로 실제 id를 조회해 채운다. `EvidenceChunkRepository`에 다음을 추가하고 `row()`에서 kind별로 부른다:
```java
    public Map<String, Long> caseIdsBySourceKey() {   // "FATALITY:arno" → id
        Map<String, Long> m = new HashMap<>();
        jdbc.query("select id, source, source_key from public_case", rs -> { m.put(rs.getString("source") + ":" + rs.getString("source_key"), rs.getLong("id")); });
        return m;
    }
    public Map<String, Long> guideIdsByNo() {
        Map<String, Long> m = new HashMap<>();
        jdbc.query("select id, guide_no from kosha_guide", rs -> { m.put(rs.getString("guide_no"), rs.getLong("id")); });
        return m;
    }
    public Map<String, Long> lawIdsByKey() {           // "lawId:no:sub:para" → id
        Map<String, Long> m = new HashMap<>();
        jdbc.query("select id, law_id, article_no, article_sub, paragraph_no from law_article",
                rs -> { m.put(rs.getString("law_id") + ":" + rs.getInt("article_no") + ":" + rs.getInt("article_sub") + ":" + rs.getInt("paragraph_no"), rs.getLong("id")); });
        return m;
    }
```
`loadChunksFrom` 시작 시 세 맵을 한 번 읽고, `row()`에서 refId를 `switch(kind)`로 채운다(GUIDE: `refKey.split("#")[0]`, LAW child: refKey 그대로, LAW parent: refKey에서 `#p` 제거 후 `:0` 첫 항 id 또는 0). 못 찾으면 0.

`PublicCacheSeedLoader.run`을:
```java
    private final EvidenceSeedLoader evidenceSeedLoader;
    @Override public void run(ApplicationArguments args) { loadCases(); loadGuides(); evidenceSeedLoader.load(); }
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.index.EvidenceSeedLoaderIT`
Expected: PASS

```bash
git add backend/src/main/java/io/saife/evidence backend/src/main/java/io/saife/publicapi/service/PublicCacheSeedLoader.java backend/src/test/java/io/saife/evidence/index
git commit -m "feat(evidence): 시드 로더 확장 — 조문·MSDS·근거 청크(벡터) 적재, parent 2패스"
```

---

### Task 4: 미디어 프록시 — `MediaCache` + `MediaController` + 프리페치

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/media/MediaCache.java`
- Create: `backend/src/main/java/io/saife/evidence/media/MediaController.java`
- Modify: `EvidenceAdminController` (`POST /api/admin/media/prefetch`)
- Modify: `backend/src/main/java/io/saife/common/error/GlobalExceptionHandler.java` — 없음(404는 `NotFoundException` 재사용)
- Test: `backend/src/test/java/io/saife/evidence/media/MediaCacheTest.java`, `MediaControllerTest.java`

**Interfaces:**
- Produces:
  - `MediaCache.fetch(String key, String originUrl, String ext) → Optional<Path>`(디스크 캐시 우선, 없으면 원본 다운로드; 원본 실패·허용 호스트 아님·빈 바디면 empty, 캐시 안 함)
  - `MediaCache.thumbnail(Path original, int width) → Optional<Path>`(JPEG, `{name}_{w}.jpg`)
  - `GET /api/media/case/{caseId}/photo[?w=]`, `GET /api/media/guide/{guideNo}.pdf`
  - `POST /api/admin/media/prefetch?scenario=demo` → `{photos, pdfs}`

- [ ] **Step 1: 실패하는 테스트**

`MediaCacheTest.java`:
```java
package io.saife.evidence.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaCacheTest {
    private byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void 첫_요청은_원본_두번째는_디스크(@TempDir Path dir) throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        byte[] body = png(900, 600);
        MediaCache cache = new MediaCache(dir, url -> { downloads.incrementAndGet(); return body; });
        Optional<Path> a = cache.fetch("case/1", "https://portal.kosha.or.kr/x.png", "png");
        Optional<Path> b = cache.fetch("case/1", "https://portal.kosha.or.kr/x.png", "png");
        assertThat(a).isPresent(); assertThat(b).isPresent();
        assertThat(downloads.get()).isEqualTo(1);
        assertThat(Files.size(a.get())).isEqualTo(body.length);
    }

    @Test
    void 원본_실패는_캐시하지_않는다(@TempDir Path dir) {
        MediaCache cache = new MediaCache(dir, url -> { throw new RuntimeException("404"); });
        assertThat(cache.fetch("case/2", "https://portal.kosha.or.kr/y.png", "png")).isEmpty();
        assertThat(Files.exists(dir.resolve("case/2.png"))).isFalse();
        MediaCache empty = new MediaCache(dir, url -> new byte[0]);
        assertThat(empty.fetch("case/3", "https://portal.kosha.or.kr/z.png", "png")).isEmpty();
    }

    @Test
    void 허용_호스트가_아니면_요청하지_않는다(@TempDir Path dir) {
        AtomicInteger downloads = new AtomicInteger();
        MediaCache cache = new MediaCache(dir, url -> { downloads.incrementAndGet(); return new byte[]{1}; });
        assertThat(cache.fetch("case/4", "https://evil.example/a.png", "png")).isEmpty();
        assertThat(downloads.get()).isZero();
    }

    @Test
    void 썸네일_생성과_재사용(@TempDir Path dir) throws Exception {
        MediaCache cache = new MediaCache(dir, url -> png(900, 600));
        Path original = cache.fetch("case/5", "https://portal.kosha.or.kr/x.png", "png").orElseThrow();
        Path t1 = cache.thumbnail(original, 320).orElseThrow();
        assertThat(t1.getFileName().toString()).isEqualTo("5_320.jpg");
        BufferedImage img = ImageIO.read(t1.toFile());
        assertThat(img.getWidth()).isEqualTo(320);
        long mtime = Files.getLastModifiedTime(t1).toMillis();
        Path t2 = cache.thumbnail(original, 320).orElseThrow();
        assertThat(Files.getLastModifiedTime(t2).toMillis()).isEqualTo(mtime);
    }
}
```

`MediaControllerTest.java` (WebMvc 슬라이스 없이 서비스 가짜):
```java
package io.saife.evidence.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.common.error.NotFoundException;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

class MediaControllerTest {
    @Test
    void DB에_없는_id는_404(@TempDir Path dir) {
        PublicCaseRepository cases = mock(PublicCaseRepository.class);
        when(cases.findById(9L)).thenReturn(Optional.empty());
        MediaController c = new MediaController(new MediaCache(dir, u -> new byte[]{1}), cases, mock(KoshaGuideRepository.class));
        org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class, () -> c.casePhoto(9L, null));
    }

    @Test
    void 사진이_없는_사례도_404(@TempDir Path dir) {
        PublicCaseRepository cases = mock(PublicCaseRepository.class);
        when(cases.findById(1L)).thenReturn(Optional.of(PublicCase.builder().id(1L).source("DISASTER").sourceKey("k").build()));
        MediaController c = new MediaController(new MediaCache(dir, u -> new byte[]{1}), cases, mock(KoshaGuideRepository.class));
        org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class, () -> c.casePhoto(1L, null));
    }

    @Test
    void 원본_실패는_캐시하지_않는다(@TempDir Path dir) {
        PublicCaseRepository cases = mock(PublicCaseRepository.class);
        when(cases.findById(1L)).thenReturn(Optional.of(PublicCase.builder().id(1L).source("FATALITY").sourceKey("k").imageUrl("https://portal.kosha.or.kr/a.png").build()));
        MediaController c = new MediaController(new MediaCache(dir, u -> { throw new RuntimeException("503"); }), cases, mock(KoshaGuideRepository.class));
        org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class, () -> c.casePhoto(1L, null));
        assertThat(Files.exists(dir.resolve("case/1.png"))).isFalse();
    }

    @Test
    void 정상이면_PNG와_캐시_헤더(@TempDir Path dir) throws Exception {
        PublicCaseRepository cases = mock(PublicCaseRepository.class);
        when(cases.findById(1L)).thenReturn(Optional.of(PublicCase.builder().id(1L).source("FATALITY").sourceKey("k").imageUrl("https://portal.kosha.or.kr/a.png").build()));
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        MediaController c = new MediaController(new MediaCache(dir, u -> png), cases, mock(KoshaGuideRepository.class));
        ResponseEntity<org.springframework.core.io.Resource> res = c.casePhoto(1L, null);
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(res.getHeaders().getCacheControl()).contains("max-age=86400");
    }
}
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`MediaCache.java`:
```java
package io.saife.evidence.media;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 원본 URL → 디스크 캐시. 허용 호스트는 공단 포털뿐(SSRF 차단). 원본 실패·빈 바디는 캐시하지 않는다.
 * 리허설에서 시연 사진이 자동으로 캐시되므로 무대 오프라인에도 남는다.
 */
@Slf4j
@Component
public class MediaCache {
    static final String ALLOWED_HOST = "portal.kosha.or.kr";
    private final Path root;
    private final Function<String, byte[]> downloader;

    @Autowired
    public MediaCache(@Value("${saife.media-dir:./data/media}") String dir) {
        this(Path.of(dir), MediaCache::httpGet);
    }

    MediaCache(Path root, Function<String, byte[]> downloader) { this.root = root; this.downloader = downloader; }

    public Optional<Path> fetch(String key, String originUrl, String ext) {
        Path file = root.resolve(key + "." + ext);
        if (Files.exists(file)) return Optional.of(file);
        if (originUrl == null || !isAllowed(originUrl)) return Optional.empty();
        try {
            byte[] body = downloader.apply(originUrl);
            if (body == null || body.length == 0) return Optional.empty();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".part");
            Files.write(tmp, body);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Optional.of(file);
        } catch (Exception e) {
            log.warn("[MEDIA] 원본 실패 {}: {}", originUrl, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Path> thumbnail(Path original, int width) {
        String name = original.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path thumb = original.resolveSibling(name + "_" + width + ".jpg");
        if (Files.exists(thumb)) return Optional.of(thumb);
        try {
            BufferedImage src = ImageIO.read(original.toFile());
            if (src == null) return Optional.empty();
            int h = Math.max(1, (int) Math.round(src.getHeight() * (width / (double) src.getWidth())));
            Image scaled = src.getScaledInstance(width, h, Image.SCALE_SMOOTH);
            BufferedImage out = new BufferedImage(width, h, BufferedImage.TYPE_INT_RGB);
            var g = out.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
            ImageIO.write(out, "jpg", thumb.toFile());
            return Optional.of(thumb);
        } catch (IOException e) {
            log.warn("[MEDIA] 썸네일 실패 {}: {}", original, e.getMessage());
            return Optional.empty();
        }
    }

    static boolean isAllowed(String url) {
        try { return ALLOWED_HOST.equalsIgnoreCase(URI.create(url).getHost()) && url.startsWith("https://"); } catch (Exception e) { return false; }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();

    static byte[] httpGet(String url) {
        try {
            HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "SAIFE/1.0 (+evidence media cache)").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            return res.body();
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
```

`MediaController.java`:
```java
package io.saife.evidence.media;

import io.saife.common.error.NotFoundException;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 사진·PDF 온디맨드 프록시. URL을 받지 않는다 — DB의 id로만 원본을 찾는다 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {
    private final MediaCache cache;
    private final PublicCaseRepository cases;
    private final KoshaGuideRepository guides;

    @GetMapping("/case/{caseId}/photo")
    public ResponseEntity<Resource> casePhoto(@PathVariable Long caseId, @RequestParam(value = "w", required = false) Integer w) {
        PublicCase c = cases.findById(caseId).orElseThrow(() -> new NotFoundException("사례를 찾을 수 없습니다: " + caseId));
        if (c.getImageUrl() == null) throw new NotFoundException("이 사례에는 사진이 없습니다: " + caseId);
        Path file = cache.fetch("case/" + caseId, c.getImageUrl(), "png").orElseThrow(() -> new NotFoundException("사진 원본을 가져오지 못했습니다: " + caseId));
        if (w != null && w > 0 && w <= 1024) {
            Path thumb = cache.thumbnail(file, w).orElse(file);
            return ok(thumb, thumb == file ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG, null);
        }
        return ok(file, MediaType.IMAGE_PNG, null);
    }

    @GetMapping("/guide/{guideNo}.pdf")
    public ResponseEntity<Resource> guidePdf(@PathVariable String guideNo) {
        KoshaGuide g = guides.findByGuideNo(guideNo).orElseThrow(() -> new NotFoundException("지침을 찾을 수 없습니다: " + guideNo));
        Path file = cache.fetch("guide/" + guideNo.replaceAll("[^A-Za-z0-9._-]", "_"), g.getFileDownloadUrl(), "pdf")
                .orElseThrow(() -> new NotFoundException("PDF 원본을 가져오지 못했습니다: " + guideNo));
        return ok(file, MediaType.APPLICATION_PDF, "inline; filename=\"" + guideNo + ".pdf\"");
    }

    private ResponseEntity<Resource> ok(Path file, MediaType type, String disposition) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic());
        if (disposition != null) b.header("Content-Disposition", disposition);
        return b.body(new FileSystemResource(file));
    }
}
```

`EvidenceAdminController`에 프리페치:
```java
    private final MediaCache mediaCache;
    private final PublicCaseRepository publicCaseRepository;
    private final KoshaGuideRepository koshaGuideRepository;

    /** 리허설 전 1회. 제조업/사진 있는 사례 200건 + 지침 30건을 디스크에 미리 받는다 */
    @PostMapping("/media/prefetch")
    public ResponseEntity<Map<String, Integer>> prefetch(@RequestParam(defaultValue = "demo") String scenario) {
        int photos = 0, pdfs = 0;
        for (PublicCase c : publicCaseRepository.findAll()) {
            if (c.getImageUrl() == null) continue;
            if (photos >= 200) break;
            if (mediaCache.fetch("case/" + c.getId(), c.getImageUrl(), "png").flatMap(p -> mediaCache.thumbnail(p, 320)).isPresent()) photos++;
        }
        for (String kw : List.of("사다리", "도장", "용접", "고소", "방호", "지게차", "크레인", "밀폐")) {
            for (KoshaGuide g : koshaGuideRepository.searchByName(kw).stream().limit(4).toList()) {
                if (pdfs >= 30) break;
                if (mediaCache.fetch("guide/" + g.getGuideNo().replaceAll("[^A-Za-z0-9._-]", "_"), g.getFileDownloadUrl(), "pdf").isPresent()) pdfs++;
            }
        }
        return ResponseEntity.ok(Map.of("photos", photos, "pdfs", pdfs));
    }
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.media.*"`
Expected: PASS 8건

```bash
git add backend/src/main/java/io/saife/evidence backend/src/test/java/io/saife/evidence/media
git commit -m "feat(evidence): 미디어 프록시 — 공단 사진·PDF 온디맨드 디스크 캐시, 320px 썸네일, 프리페치"
```

---

### Task 5: 크롤러 PDF 텍스트 훅 + `GuideTextSource` 구현 + 시스템 상태

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/index/GuidePdfTextSource.java`
- Create: `backend/src/main/java/io/saife/evidence/SystemStatusController.java`
- Modify: `frontend/src/api/endpoints.ts` (`SYSTEM_STATUS`) — 타입은 B 계획
- Test: `backend/src/test/java/io/saife/evidence/index/GuidePdfTextSourceTest.java`

**Interfaces:**
- Produces:
  - `GuidePdfTextSource implements GuideTextSource` — `MediaCache.fetch("guide/{no}", url, "pdf")` → `PdfTextExtractor.extract(bytes)`
  - `GET /api/system/status → SystemStatus(demoMode, embeddingAvailable, evidenceChunkCount, evidenceByKind, circuitOpenHosts, lastCrawlAt)`

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.media.MediaCache;
import io.saife.publicapi.domain.KoshaGuide;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuidePdfTextSourceTest {
    @Test
    void 다운로드_실패면_빈_리스트(@TempDir Path dir) {
        GuidePdfTextSource s = new GuidePdfTextSource(new MediaCache(dir, u -> { throw new RuntimeException("x"); }));
        assertThat(s.pagesOf(KoshaGuide.builder().guideNo("G-1").guideName("g").fileDownloadUrl("https://portal.kosha.or.kr/f").build())).isEmpty();
        assertThat(s.pagesOf(KoshaGuide.builder().guideNo("G-2").guideName("g").build())).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`GuidePdfTextSource.java`:
```java
package io.saife.evidence.index;

import io.saife.evidence.chunk.PdfTextExtractor;
import io.saife.evidence.media.MediaCache;
import io.saife.publicapi.domain.KoshaGuide;
import java.nio.file.Files;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 지침 PDF → 페이지 텍스트. 파일은 MediaCache가 갖고 있다(프록시와 같은 캐시) */
@Component
@RequiredArgsConstructor
public class GuidePdfTextSource implements GuideTextSource {
    private final MediaCache cache;

    @Override
    public List<String> pagesOf(KoshaGuide guide) {
        if (guide.getFileDownloadUrl() == null) return List.of();
        return cache.fetch("guide/" + guide.getGuideNo().replaceAll("[^A-Za-z0-9._-]", "_"), guide.getFileDownloadUrl(), "pdf")
                .map(p -> { try { return PdfTextExtractor.extract(Files.readAllBytes(p)); } catch (Exception e) { return List.<String>of(); } })
                .orElse(List.of());
    }
}
```

`SystemStatusController.java`:
```java
package io.saife.evidence;

import io.saife.common.config.DemoModeConfig;
import io.saife.evidence.live.LiveOrCache;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.evidence.search.QueryEmbedder;
import io.saife.publicapi.domain.CrawlCheckpoint;
import io.saife.publicapi.repository.CrawlCheckpointRepository;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 프론트 전역 상태 줄용. 데모 모드·벡터 가능 여부·근거 수·회로 상태 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemStatusController {
    public record SystemStatus(boolean demoMode, boolean embeddingAvailable, long evidenceChunkCount,
                               Map<String, Long> evidenceByKind, Set<String> circuitOpenHosts, OffsetDateTime lastCrawlAt) {}

    private final DemoModeConfig demoModeConfig;
    private final QueryEmbedder embedder;
    private final EvidenceChunkRepository chunks;
    private final LiveOrCache liveOrCache;
    private final CrawlCheckpointRepository checkpoints;

    @GetMapping("/status")
    public ResponseEntity<SystemStatus> status() {
        Map<String, Long> byKind = chunks.countByKind();
        OffsetDateTime last = checkpoints.findAll().stream().map(CrawlCheckpoint::getStartedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return ResponseEntity.ok(new SystemStatus(demoModeConfig.isDemoMode(), embedder.available(),
                byKind.values().stream().mapToLong(Long::longValue).sum(), byKind, liveOrCache.openHosts(), last));
    }
}
```
`frontend/src/api/endpoints.ts`에 `export const SYSTEM_STATUS = "/api/system/status";` 추가.

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test`
Expected: 전체 PASS (IT 3건은 5433 DB)

```bash
git add backend/src/main/java/io/saife/evidence frontend/src/api/endpoints.ts backend/src/test
git commit -m "feat(evidence): 지침 PDF 텍스트 소스와 /api/system/status"
```

---

### Task 6: 시드 생성 실행 절차 (1회, 키 있음)

**Files:** 산출물만 — `backend/src/main/resources/seed/*.jsonl.gz` 5개, `docs/experiments/README.md`에 절차 기록

- [ ] **Step 1: 백엔드를 키와 5433 DB로 기동**
  `.env`를 export한 뒤 `./gradlew bootRun --args="--spring.datasource.url=jdbc:postgresql://localhost:5433/saife"`.

- [ ] **Step 2: 수집·인덱스 순서대로 실행 (각 응답의 status가 DONE인지 확인)**
```bash
curl -X POST http://localhost:8080/api/admin/law/crawl
curl -X POST "http://localhost:8080/api/admin/public-api/crawl"          # 증분. image_url이 채워진다 (기존 행은 재수집 필요 — 아래)
curl -X POST "http://localhost:8080/api/admin/index/rebuild?kind=LAW"
curl -X POST "http://localhost:8080/api/admin/index/rebuild?kind=CASE_FATALITY"
curl -X POST "http://localhost:8080/api/admin/index/rebuild?kind=CASE_DISASTER"
curl -X POST "http://localhost:8080/api/admin/index/rebuild?kind=GUIDE"   # 1,039 PDF 다운로드 + enrichment. 20~40분. 실패 시 재실행하면 이어받는다
```
  기존 `public_case` 행에는 `image_url`이 없다. 크롤러는 `findBySourceAndSourceKey`로 중복을 건너뛰므로 **1040만** `delete from public_case where source='FATALITY'` 후 `POST /crawl/FATALITY`로 다시 받는다(10페이지, 1분). `crawl_checkpoint`의 FATALITY 행도 지운다.

- [ ] **Step 3: 내보내기와 동봉**
```bash
curl http://localhost:8080/api/admin/index/export
cp backend/data/export/*.jsonl.gz backend/src/main/resources/seed/
ls -la backend/src/main/resources/seed/    # evidence_chunk.jsonl.gz ≤ 60MB 확인
```

- [ ] **Step 4: 키 없이 클린 기동 검증**
  DB를 비우고(`docker compose -f docker-compose.dev.yml down -v` 후 재기동) `.env` 없이 `./gradlew bootRun` → 로그에 `[SEED] 근거 청크 N건 적재`, `GET /api/system/status`의 `evidenceChunkCount > 15000`, `embeddingAvailable=false`.

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/resources/seed docs/experiments/README.md
git commit -m "feat(seed): 근거 청크·조문·MSDS·사진 URL 포함 시드 동봉 — 키 없이 벡터 검색 동작"
```

---

## Self-Review

- 스펙 §4 시드, §6.4 PDF 텍스트·증분 크롤, §6.5 프록시·썸네일·프리페치, §6.6 관리자 엔드포인트(rebuild/export/law crawl/prefetch)·`/api/system/status` 모두 커버. `checkLatest`(공단 최신 등재 확인)는 B 계획의 도구 재배선에서 `PublicApiCrawler`에 추가한다.
- 플레이스홀더 없음. `IndexBuilder`가 `GuideTextSource` 인터페이스에 의존하고 구현은 Task 5 — 태스크 순서상 Task 1 테스트는 가짜로 돈다.
- Review Focus 5건 모두 테스트 존재.
