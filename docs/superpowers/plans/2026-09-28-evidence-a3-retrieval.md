# 근거 계층 A3 — 하이브리드 검색 파이프라인 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `EvidenceSearchService.search(request)` 하나로 벡터+키워드 후보 → 가중 RRF → Parent 확장 → LLM 리랭크 → 도메인 보정 → `Evidence` 카드를 돌려준다. 키가 없으면 키워드 폴백으로 같은 형태의 결과를 낸다.

**Architecture:** `io.saife.evidence.search` 패키지. 순수 함수(`TsQueryBuilder`, `HybridRrf`, `RerankScoreParser`)와 DB/모델에 붙는 얇은 어댑터(`EvidenceChunkRepository`(JdbcTemplate), `QueryEmbedder`, `LlmReranker`)를 분리해 단위 테스트가 DB·모델 없이 돈다. Inufleet `AgenticRagService` 파이프라인 이식 + §5.6 함정 회피.

**Tech Stack:** JdbcTemplate + pgvector · Spring AI `EmbeddingModel`(`gemini-embedding-2`, 768) · `ChatClient`(리랭크) · JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-28-evidence-rag-and-connectivity-design.md` §5, §5.6 · 선행: A1(엔티티·`Origin`), A2(`EvidenceKind`, `ContextualPrefix`)

## Global Constraints

- A1 Global Constraints 적용. 검색 상수는 `SearchPolicy` 한 곳: `VECTOR_WEIGHT=0.6`, `KEYWORD_WEIGHT=0.4`, `RRF_K=60`, `SIMILARITY_THRESHOLD=0.40`, `CANDIDATE_MULTIPLIER=4`, `RERANK_MAX_DOCS=40`, `RERANK_MAX_CHARS=1500`, `RERANK_MIN_SCORE=4`, `KEYWORD_MAX_TOKENS=8`, `SNIPPET_CHARS=200`, `BOOST_SAME_AXIS=0.05`, `BOOST_MANUFACTURING=0.03`, `BOOST_HAS_IMAGE=0.02`.
- 검색 어댑터는 예외를 밖으로 내지 않는다(빈 리스트 + `log.warn`).
- 리랭크 옵션은 반드시 `thinkingBudget(0)`, `responseMimeType("application/json")`, `maxOutputTokens(500)`, `temperature(0.0)`, `.model(...)` 명시.
- 벡터는 JDBC 파라미터 `?::vector`에 `"[0.1,0.2,...]"` 문자열로 넘긴다.
- `LawArticle`의 `citation()`, `EvidenceChunk` 엔티티는 읽기만 한다(쓰기는 A4 IndexBuilder).

## Review Focus

1. 질의가 조사 붙은 한 단어("사다리에서")일 때 키워드 검색이 `사다리`로도 맞아야 한다. → Task 1 `TsQueryBuilderTest.조사를_벗긴_변형을_함께`.
2. 벡터 후보와 키워드 후보에 같은 청크가 있으면 RRF에서 1위가 되고 중복 없이 한 번만 나와야 한다. → Task 2 `HybridRrfTest.양쪽에_있는_문서가_1위`.
3. 리랭크 점수 배열 길이가 문서 수와 다르면(모델이 하나 빼먹음) 원본 순서 폴백이어야 하고 예외가 나면 안 된다. → Task 4 `RerankScoreParserTest.길이_불일치는_null`.
4. parent 조회 SQL이 실패해도 child가 결과에 남아야 한다. → Task 3 `ParentChunkExpanderTest.parent_조회_실패면_child_유지`.
5. 데모 모드(키 없음)에서 `search`가 `origin=KEYWORD_FALLBACK`인 결과를 돌려주고 임베딩·리랭크를 호출하지 않아야 한다. → Task 6 `EvidenceSearchServiceTest.키_없으면_키워드_폴백`.

---

### Task 1: `SearchPolicy` + `TsQueryBuilder`

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/search/SearchPolicy.java`
- Create: `backend/src/main/java/io/saife/evidence/search/TsQueryBuilder.java`
- Test: `backend/src/test/java/io/saife/evidence/search/TsQueryBuilderTest.java`

**Interfaces:**
- Produces: `TsQueryBuilder.build(String query) → String`(`to_tsquery('simple', ...)`에 넣을 식. 빈 문자열이면 키워드 검색 생략), `TsQueryBuilder.tokens(String query) → List<String>`.

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TsQueryBuilderTest {
    @Test
    void 토큰을_OR로_묶는다() {
        assertThat(TsQueryBuilder.build("사다리 천장 페인트")).isEqualTo("'사다리' | '천장' | '페인트'");
    }

    @Test
    void 조사를_벗긴_변형을_함께() {
        String q = TsQueryBuilder.build("사다리에서 작업을");
        assertThat(q).contains("'사다리에서'").contains("'사다리'").contains("'작업을'").contains("'작업'");
    }

    @Test
    void 한_글자_토큰과_구두점은_버리고_8개까지만() {
        String q = TsQueryBuilder.build("a, 가 나다 라마 바사 아자 차카 타파 하가 거너 더러");
        assertThat(q).doesNotContain("'a'").doesNotContain("'가'");
        assertThat(TsQueryBuilder.tokens("나다 라마 바사 아자 차카 타파 하가 거너 더러")).hasSize(SearchPolicy.KEYWORD_MAX_TOKENS);
    }

    @Test
    void 비어_있으면_빈_문자열() {
        assertThat(TsQueryBuilder.build("")).isEmpty();
        assertThat(TsQueryBuilder.build(null)).isEmpty();
        assertThat(TsQueryBuilder.build("' | &")).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.TsQueryBuilderTest`
Expected: FAIL

- [ ] **Step 3: 구현**

`SearchPolicy.java`:
```java
package io.saife.evidence.search;

/** 검색 상수. Inufleet 검증값을 그대로 쓰고, 여기 한 곳에서만 바꾼다 */
public final class SearchPolicy {
    private SearchPolicy() {}
    public static final double VECTOR_WEIGHT = 0.6;
    public static final double KEYWORD_WEIGHT = 0.4;
    public static final int RRF_K = 60;
    public static final double SIMILARITY_THRESHOLD = 0.40;
    public static final int CANDIDATE_MULTIPLIER = 4;
    public static final int RERANK_MAX_DOCS = 40;
    public static final int RERANK_MAX_CHARS = 1500;
    public static final int RERANK_MIN_SCORE = 4;
    public static final int KEYWORD_MAX_TOKENS = 8;
    public static final int SNIPPET_CHARS = 200;
    public static final double BOOST_SAME_AXIS = 0.05;
    public static final double BOOST_MANUFACTURING = 0.03;
    public static final double BOOST_HAS_IMAGE = 0.02;
    public static final int EMBEDDING_DIMENSIONS = 768;
}
```

`TsQueryBuilder.java`:
```java
package io.saife.evidence.search;

import java.util.*;

/**
 * 자연어 질의 → `to_tsquery('simple', …)` 식.
 *
 * <p>Inufleet의 `plainto_tsquery`(모든 토큰 AND)는 조사가 붙은 한국어에서 거의 안 맞았다.
 * 여기서는 토큰을 OR로 묶고, 조사를 벗긴 변형을 함께 넣는다.
 */
public final class TsQueryBuilder {
    private TsQueryBuilder() {}
    private static final String[] PARTICLES = {"에서의", "으로는", "에서는", "에서", "으로", "부터", "까지", "에게", "은", "는", "이", "가", "을", "를", "와", "과", "의", "도", "에", "로"};

    public static List<String> tokens(String query) {
        if (query == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : query.split("[\\s,./()\\[\\]{}:;!?\"'|&<>~`^*+=\\\\-]+")) {
            String t = raw.strip();
            if (t.length() < 2) continue;
            out.add(t);
            if (out.size() >= SearchPolicy.KEYWORD_MAX_TOKENS) break;
        }
        return new ArrayList<>(out);
    }

    public static String build(String query) {
        List<String> toks = tokens(query);
        if (toks.isEmpty()) return "";
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String t : toks) {
            terms.add(t);
            String stripped = stripParticle(t);
            if (stripped != null) terms.add(stripped);
        }
        StringJoiner sj = new StringJoiner(" | ");
        terms.forEach(t -> sj.add("'" + t.replace("'", "") + "'"));
        return sj.toString();
    }

    /** 뒤에 붙은 조사를 하나 벗긴다. 남는 길이가 2 미만이면 null */
    static String stripParticle(String token) {
        for (String p : PARTICLES) {
            if (token.endsWith(p) && token.length() - p.length() >= 2) return token.substring(0, token.length() - p.length());
        }
        return null;
    }
}
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.TsQueryBuilderTest`
Expected: PASS 4건

```bash
git add backend/src/main/java/io/saife/evidence/search backend/src/test/java/io/saife/evidence/search
git commit -m "feat(evidence): 검색 정책 상수와 tsquery 빌더(OR 매칭·조사 제거)"
```

---

### Task 2: `ChunkHit` + `HybridRrf`

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/search/ChunkHit.java`
- Create: `backend/src/main/java/io/saife/evidence/search/HybridRrf.java`
- Test: `backend/src/test/java/io/saife/evidence/search/HybridRrfTest.java`

**Interfaces:**
- Produces:
  - `record ChunkHit(long id, EvidenceKind kind, long refId, String refKey, String chunkLevel, Long parentId, String sectionTitle, String title, String text, Map<String,Object> metadata, double score)` + `ChunkHit withScore(double)`, `ChunkHit withTextAndId(long id, String text, String chunkLevel, Long parentId)`
  - `HybridRrf.fuse(List<ChunkHit> vector, List<ChunkHit> keyword, int limit) → List<ChunkHit>` (score = RRF 점수)

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.EvidenceKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HybridRrfTest {
    private ChunkHit hit(long id) {
        return new ChunkHit(id, EvidenceKind.CASE_DISASTER, id, "k" + id, "child", null, null, "t" + id, "x", Map.of(), 0);
    }

    @Test
    void 양쪽에_있는_문서가_1위() {
        List<ChunkHit> out = HybridRrf.fuse(List.of(hit(1), hit(2), hit(3)), List.of(hit(3), hit(4)), 10);
        assertThat(out.get(0).id()).isEqualTo(3);
        assertThat(out).extracting(ChunkHit::id).doesNotHaveDuplicates();
        assertThat(out).hasSize(4);
    }

    @Test
    void 한쪽이_비면_다른_쪽_순서() {
        List<ChunkHit> out = HybridRrf.fuse(List.of(hit(1), hit(2)), List.of(), 10);
        assertThat(out).extracting(ChunkHit::id).containsExactly(1L, 2L);
    }

    @Test
    void limit을_지킨다_그리고_점수는_RRF() {
        List<ChunkHit> out = HybridRrf.fuse(List.of(hit(1), hit(2), hit(3)), List.of(hit(1)), 2);
        assertThat(out).hasSize(2);
        double expected = SearchPolicy.VECTOR_WEIGHT / (SearchPolicy.RRF_K + 1) + SearchPolicy.KEYWORD_WEIGHT / (SearchPolicy.RRF_K + 1);
        assertThat(out.get(0).score()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void 둘_다_비면_빈_리스트() {
        assertThat(HybridRrf.fuse(List.of(), List.of(), 5)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.HybridRrfTest`
Expected: FAIL

- [ ] **Step 3: 구현**

`ChunkHit.java`:
```java
package io.saife.evidence.search;

import io.saife.evidence.EvidenceKind;
import java.util.Map;

/** 검색 파이프라인을 흐르는 청크 한 건. score는 단계마다 의미가 다르다(유사도 → RRF → 리랭크 → 보정) */
public record ChunkHit(long id, EvidenceKind kind, long refId, String refKey, String chunkLevel, Long parentId,
                       String sectionTitle, String title, String text, Map<String, Object> metadata, double score) {
    public ChunkHit withScore(double s) {
        return new ChunkHit(id, kind, refId, refKey, chunkLevel, parentId, sectionTitle, title, text, metadata, s);
    }
    /** parent로 치환할 때: id·텍스트·레벨만 바뀌고 나머지는 child 것을 유지 */
    public ChunkHit asParent(long parentRowId, String parentText, String parentTitle) {
        return new ChunkHit(parentRowId, kind, refId, refKey, "parent", null, sectionTitle, parentTitle, parentText, metadata, score);
    }
}
```

`HybridRrf.java`:
```java
package io.saife.evidence.search;

import java.util.*;

/** 가중 Reciprocal Rank Fusion. score[id] += w / (K + rank + 1). Inufleet HybridSearchService 이식 */
public final class HybridRrf {
    private HybridRrf() {}

    public static List<ChunkHit> fuse(List<ChunkHit> vector, List<ChunkHit> keyword, int limit) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, ChunkHit> docs = new LinkedHashMap<>();
        for (int i = 0; i < vector.size(); i++) {
            ChunkHit h = vector.get(i);
            scores.merge(h.id(), SearchPolicy.VECTOR_WEIGHT / (SearchPolicy.RRF_K + i + 1), Double::sum);
            docs.putIfAbsent(h.id(), h);
        }
        for (int i = 0; i < keyword.size(); i++) {
            ChunkHit h = keyword.get(i);
            scores.merge(h.id(), SearchPolicy.KEYWORD_WEIGHT / (SearchPolicy.RRF_K + i + 1), Double::sum);
            docs.putIfAbsent(h.id(), h);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> docs.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }
}
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.HybridRrfTest`
Expected: PASS 4건

```bash
git add backend/src/main/java/io/saife/evidence/search backend/src/test/java/io/saife/evidence/search
git commit -m "feat(evidence): ChunkHit과 가중 RRF 합산(0.6/0.4, K=60)"
```

---

### Task 3: `EvidenceChunkRepository`(JdbcTemplate) + `ParentChunkExpander`

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/search/EvidenceChunkRepository.java`
- Create: `backend/src/main/java/io/saife/evidence/search/ParentChunkExpander.java`
- Test: `backend/src/test/java/io/saife/evidence/search/ParentChunkExpanderTest.java` (Mockito)
- Test: `backend/src/test/java/io/saife/evidence/search/EvidenceChunkRepositoryIT.java` (`@SpringBootTest`, 5433)

**Interfaces:**
- Produces:
  - `EvidenceChunkRepository.vectorSearch(float[] q, Set<EvidenceKind> kinds, String axis, int limit) → List<ChunkHit>`
  - `EvidenceChunkRepository.keywordSearch(String tsquery, Set<EvidenceKind> kinds, String axis, int limit) → List<ChunkHit>`
  - `EvidenceChunkRepository.findParents(Set<Long> ids) → Map<Long, ChunkHit>`
  - `EvidenceChunkRepository.insertBatch(List<ChunkRow> rows)`(A4가 쓴다), `record ChunkRow(EvidenceKind kind, long refId, String refKey, String chunkLevel, Long parentId, boolean searchable, String sectionTitle, String title, String text, Map<String,Object> metadata, float[] embedding)`
  - `EvidenceChunkRepository.findIdByRefKey(EvidenceKind, String) → Optional<Long>`, `countByKind() → Map<String,Long>`, `deleteByKind(EvidenceKind)`
  - `ParentChunkExpander.expand(List<ChunkHit>) → List<ChunkHit>`

- [ ] **Step 1: 실패하는 확장기 테스트**

```java
package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;

import io.saife.evidence.EvidenceKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParentChunkExpanderTest {
    private ChunkHit child(long id, Long parentId, double score) {
        return new ChunkHit(id, EvidenceKind.GUIDE, 1, "G#c" + id, "child", parentId, "s", "t", "child text " + id, Map.of(), score);
    }
    private ChunkHit parentRow(long id) {
        return new ChunkHit(id, EvidenceKind.GUIDE, 1, "G#p", "parent", null, "s", "T", "parent text", Map.of(), 0);
    }

    @Test
    void 같은_parent의_child들은_parent_하나로_합쳐지고_최고_점수를_유지한다() {
        EvidenceChunkRepository repo = mock(EvidenceChunkRepository.class);
        when(repo.findParents(anySet())).thenReturn(Map.of(100L, parentRow(100)));
        List<ChunkHit> out = new ParentChunkExpander(repo).expand(List.of(child(1, 100L, 0.9), child(2, 100L, 0.5), child(3, null, 0.7)));
        assertThat(out).hasSize(2);
        assertThat(out.get(0).id()).isEqualTo(100);
        assertThat(out.get(0).chunkLevel()).isEqualTo("parent");
        assertThat(out.get(0).text()).isEqualTo("parent text");
        assertThat(out.get(0).score()).isEqualTo(0.9);
        assertThat(out.get(1).id()).isEqualTo(3);
    }

    @Test
    void parent_조회_실패면_child_유지() {
        EvidenceChunkRepository repo = mock(EvidenceChunkRepository.class);
        when(repo.findParents(anySet())).thenReturn(Map.of());
        List<ChunkHit> out = new ParentChunkExpander(repo).expand(List.of(child(1, 100L, 0.9)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(1);
    }

    @Test
    void 순서는_점수_내림차순() {
        EvidenceChunkRepository repo = mock(EvidenceChunkRepository.class);
        when(repo.findParents(anySet())).thenReturn(Map.of(100L, parentRow(100)));
        List<ChunkHit> out = new ParentChunkExpander(repo).expand(List.of(child(3, null, 0.95), child(1, 100L, 0.9)));
        assertThat(out).extracting(ChunkHit::id).containsExactly(3L, 100L);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.ParentChunkExpanderTest`
Expected: FAIL

- [ ] **Step 3: 리포지토리·확장기 구현**

`EvidenceChunkRepository.java`:
```java
package io.saife.evidence.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.EvidenceKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * evidence_chunk 네이티브 접근. 벡터·tsv는 JPA로 다루지 않는다.
 * 검색 메서드는 예외를 밖으로 내지 않는다 — 빈 리스트가 폴백이다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class EvidenceChunkRepository {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record ChunkRow(EvidenceKind kind, long refId, String refKey, String chunkLevel, Long parentId, boolean searchable,
                           String sectionTitle, String title, String text, Map<String, Object> metadata, float[] embedding) {}

    private static final String SELECT = """
            select id, kind, ref_id, ref_key, chunk_level, parent_id, section_title, title, text, metadata::text as metadata
            """;

    public List<ChunkHit> vectorSearch(float[] q, Set<EvidenceKind> kinds, String axis, int limit) {
        try {
            String v = toVectorLiteral(q);
            return jdbc.query(SELECT + ", 1 - (embedding <=> ?::vector) as score from evidence_chunk "
                            + "where searchable and kind = any(?) and (? is null or metadata->>'accidentType' = ?) "
                            + "and embedding is not null and 1 - (embedding <=> ?::vector) >= ? "
                            + "order by embedding <=> ?::vector limit ?",
                    mapper(), v, kindsArray(kinds), axis, axis, v, SearchPolicy.SIMILARITY_THRESHOLD, v, limit);
        } catch (Exception e) {
            log.warn("[SEARCH] 벡터 검색 실패 — 빈 결과: {}", e.getMessage());
            return List.of();
        }
    }

    public List<ChunkHit> keywordSearch(String tsquery, Set<EvidenceKind> kinds, String axis, int limit) {
        if (tsquery == null || tsquery.isBlank()) return List.of();
        try {
            return jdbc.query(SELECT + ", ts_rank_cd(tsv, to_tsquery('simple', ?)) as score from evidence_chunk "
                            + "where searchable and kind = any(?) and (? is null or metadata->>'accidentType' = ?) "
                            + "and tsv @@ to_tsquery('simple', ?) order by score desc limit ?",
                    mapper(), tsquery, kindsArray(kinds), axis, axis, tsquery, limit);
        } catch (Exception e) {
            log.warn("[SEARCH] 키워드 검색 실패 — 빈 결과: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<Long, ChunkHit> findParents(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            List<ChunkHit> rows = jdbc.query(SELECT + ", 0 as score from evidence_chunk where id = any(?)",
                    mapper(), ids.toArray(Long[]::new));
            Map<Long, ChunkHit> out = new HashMap<>();
            rows.forEach(r -> out.put(r.id(), r));
            return out;
        } catch (Exception e) {
            log.warn("[SEARCH] parent 조회 실패 — child 유지: {}", e.getMessage());
            return Map.of();
        }
    }

    public Optional<Long> findIdByRefKey(EvidenceKind kind, String refKey) {
        List<Long> ids = jdbc.queryForList("select id from evidence_chunk where kind = ? and ref_key = ?", Long.class, kind.name(), refKey);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public Map<String, Long> countByKind() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("select kind, count(*) as n from evidence_chunk group by kind order by kind",
                rs -> { out.put(rs.getString("kind"), rs.getLong("n")); });
        return out;
    }

    public void deleteByKind(EvidenceKind kind) {
        jdbc.update("delete from evidence_chunk where kind = ?", kind.name());
    }

    /** 500건 배치. parentId는 호출자가 먼저 parent를 넣고 id를 받아 채운다 */
    public List<Long> insertBatch(List<ChunkRow> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (ChunkRow r : rows) {
            Long id = jdbc.queryForObject("""
                    insert into evidence_chunk (kind, ref_id, ref_key, chunk_level, parent_id, searchable, section_title, title, text, metadata, embedding)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
                    on conflict (kind, ref_key) do update set text = excluded.text, title = excluded.title, metadata = excluded.metadata,
                        embedding = excluded.embedding, parent_id = excluded.parent_id, searchable = excluded.searchable, updated_at = now()
                    returning id
                    """, Long.class,
                    r.kind().name(), r.refId(), r.refKey(), r.chunkLevel(), r.parentId(), r.searchable(), r.sectionTitle(),
                    r.title(), r.text(), toJson(r.metadata()), r.embedding() == null ? null : toVectorLiteral(r.embedding()));
            ids.add(id);
        }
        return ids;
    }

    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }

    private String[] kindsArray(Set<EvidenceKind> kinds) {
        return (kinds == null || kinds.isEmpty() ? EnumSet.allOf(EvidenceKind.class) : kinds).stream().map(Enum::name).toArray(String[]::new);
    }

    private String toJson(Map<String, Object> m) {
        try { return objectMapper.writeValueAsString(m == null ? Map.of() : m); } catch (Exception e) { return "{}"; }
    }

    private RowMapper<ChunkHit> mapper() {
        return (ResultSet rs, int i) -> new ChunkHit(rs.getLong("id"), EvidenceKind.valueOf(rs.getString("kind")), rs.getLong("ref_id"),
                rs.getString("ref_key"), rs.getString("chunk_level"), (Long) rs.getObject("parent_id"), rs.getString("section_title"),
                rs.getString("title"), rs.getString("text"), parse(rs.getString("metadata")), rs.getDouble("score"));
    }

    private Map<String, Object> parse(String json) {
        try { return json == null ? Map.of() : objectMapper.readValue(json, MAP); } catch (Exception e) { return Map.of(); }
    }
}
```

`ParentChunkExpander.java`:
```java
package io.saife.evidence.search;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** child → parent 치환. 같은 parent는 1건(최고 점수). parent를 못 찾으면 child 유지(Inufleet은 여기서 child를 버렸다) */
@Component
@RequiredArgsConstructor
public class ParentChunkExpander {
    private final EvidenceChunkRepository repository;

    public List<ChunkHit> expand(List<ChunkHit> hits) {
        if (hits.isEmpty()) return hits;
        Set<Long> parentIds = new LinkedHashSet<>();
        for (ChunkHit h : hits) if ("child".equals(h.chunkLevel()) && h.parentId() != null) parentIds.add(h.parentId());
        Map<Long, ChunkHit> parents = repository.findParents(parentIds);
        Map<Long, ChunkHit> merged = new LinkedHashMap<>();   // key: 결과 id
        for (ChunkHit h : hits) {
            ChunkHit p = h.parentId() == null ? null : parents.get(h.parentId());
            if (p == null) { merged.putIfAbsent(h.id(), h); continue; }
            ChunkHit asParent = h.asParent(p.id(), p.text(), p.title());
            merged.merge(p.id(), asParent, (a, b) -> a.score() >= b.score() ? a : b);
        }
        return merged.values().stream().sorted(Comparator.comparingDouble(ChunkHit::score).reversed()).toList();
    }
}
```

- [ ] **Step 4: 확장기 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.ParentChunkExpanderTest`
Expected: PASS 3건

- [ ] **Step 5: 리포지토리 통합 테스트(5433 DB)**

```java
package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.EvidenceKind;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EvidenceChunkRepositoryIT {
    @Autowired EvidenceChunkRepository repo;

    private float[] unit(int hot) { float[] v = new float[SearchPolicy.EMBEDDING_DIMENSIONS]; v[hot] = 1f; return v; }

    @Test
    void 벡터_키워드_parent_라운드트립() {
        List<Long> pid = repo.insertBatch(List.of(new EvidenceChunkRepository.ChunkRow(EvidenceKind.GUIDE, 1, "IT#p1", "parent", null, false, "s", "P", "parent text", Map.of(), null)));
        repo.insertBatch(List.of(
                new EvidenceChunkRepository.ChunkRow(EvidenceKind.GUIDE, 1, "IT#c1", "child", pid.get(0), true, "s", "사다리 지침", "사다리 작업 전 확인 사항", Map.of("accidentType", "FALL"), unit(0)),
                new EvidenceChunkRepository.ChunkRow(EvidenceKind.GUIDE, 1, "IT#c2", "child", pid.get(0), true, "s", "용접 지침", "용접 화기 작업", Map.of("accidentType", "FIRE"), unit(1))));
        List<ChunkHit> vec = repo.vectorSearch(unit(0), Set.of(EvidenceKind.GUIDE), null, 5);
        assertThat(vec).isNotEmpty();
        assertThat(vec.get(0).refKey()).isEqualTo("IT#c1");
        assertThat(vec.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        List<ChunkHit> kw = repo.keywordSearch(TsQueryBuilder.build("사다리에서"), Set.of(EvidenceKind.GUIDE), "FALL", 5);
        assertThat(kw).extracting(ChunkHit::refKey).contains("IT#c1").doesNotContain("IT#c2");
        assertThat(repo.findParents(Set.of(pid.get(0)))).containsKey(pid.get(0));
        assertThat(repo.vectorSearch(unit(0), Set.of(EvidenceKind.GUIDE), "FIRE", 5)).extracting(ChunkHit::refKey).doesNotContain("IT#c1");
    }
}
```

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.EvidenceChunkRepositoryIT`
Expected: PASS (V9 적용된 5433 DB 필요)

- [ ] **Step 6: 커밋**

```bash
git add backend/src/main/java/io/saife/evidence/search backend/src/test/java/io/saife/evidence/search
git commit -m "feat(evidence): evidence_chunk 네이티브 리포지토리(벡터·tsv·parent)와 Parent 확장기"
```

---

### Task 4: `RerankScoreParser` + `LlmReranker` + 프롬프트

**Files:**
- Create: `backend/src/main/resources/prompts/evidence-rerank.txt`
- Create: `backend/src/main/java/io/saife/evidence/search/RerankScoreParser.java`
- Create: `backend/src/main/java/io/saife/evidence/search/LlmReranker.java`
- Test: `backend/src/test/java/io/saife/evidence/search/RerankScoreParserTest.java`, `LlmRerankerTest.java`

**Interfaces:**
- Produces: `RerankScoreParser.parse(String content, int expected) → int[]|null`, `LlmReranker.rerank(String query, List<ChunkHit> candidates, int topK) → List<ChunkHit>`(score=리랭크 점수/10.0)

- [ ] **Step 1: 프롬프트 (Inufleet `rag-rerank.txt` 그대로)**

`prompts/evidence-rerank.txt`:
```
<system-prompt>
<role>다음 질문에 대한 각 문서의 관련성을 0~10 점수로 평가하세요.</role>

<section name="scoring-criteria">
<definitions>
<def term="10">질문에 직접적으로 답변하는 핵심 정보 포함</def>
<def term="7-9">질문과 매우 관련되어 답변에 도움이 되는 정보</def>
<def term="4-6">부분적으로 관련되거나 배경 정보</def>
<def term="1-3">관련성이 낮거나 간접적</def>
<def term="0">전혀 무관</def>
</definitions>
</section>

<context type="query">{query}</context>

<context type="documents">{documents}</context>

<section name="output">
<rule>반드시 JSON 정수 배열만 출력하세요. 다른 텍스트, 설명, 마크다운 없이 배열만.</rule>
<example type="correct">[8, 3, 9, 5]</example>
</section>
</system-prompt>
```

- [ ] **Step 2: 실패하는 테스트**

`RerankScoreParserTest.java`:
```java
package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class RerankScoreParserTest {
    @Test
    void 정수_배열() { assertThat(RerankScoreParser.parse("[8, 3, 9]", 3)).containsExactly(8, 3, 9); }

    @Test
    void 앞뒤_텍스트와_코드펜스를_무시() {
        assertThat(RerankScoreParser.parse("```json\n[1,2]\n```", 2)).containsExactly(1, 2);
    }

    @Test
    void 객체_배열은_첫_숫자_필드() {
        assertThat(RerankScoreParser.parse("[{\"score\":7},{\"relevance\":2}]", 2)).containsExactly(7, 2);
    }

    @Test
    void 길이_불일치는_null() {
        assertThat(RerankScoreParser.parse("[8, 3]", 3)).isNull();
        assertThat(RerankScoreParser.parse("[8, 3, 9, 1]", 3)).isNull();
    }

    @Test
    void 깨진_입력은_null() {
        assertThat(RerankScoreParser.parse("no json", 2)).isNull();
        assertThat(RerankScoreParser.parse(null, 2)).isNull();
    }
}
```

`LlmRerankerTest.java`:
```java
package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saife.evidence.EvidenceKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class LlmRerankerTest {
    private ChunkHit hit(long id) { return new ChunkHit(id, EvidenceKind.GUIDE, 1, "k" + id, "child", null, null, "t", "text" + id, Map.of(), 0.1 * id); }

    private LlmReranker reranker(String answer) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(client);
        if (answer == null) when(client.prompt().user(any(String.class)).options(any()).call().content()).thenThrow(new RuntimeException("x"));
        else when(client.prompt().user(any(String.class)).options(any()).call().content()).thenReturn(answer);
        return new LlmReranker(builder, "<p>{query}{documents}</p>");
    }

    @Test
    void 점수_내림차순_4점_미만_제외_topK() {
        List<ChunkHit> out = reranker("[3, 9, 5, 8]").rerank("q", List.of(hit(1), hit(2), hit(3), hit(4)), 2);
        assertThat(out).extracting(ChunkHit::id).containsExactly(2L, 4L);
        assertThat(out.get(0).score()).isEqualTo(0.9);
    }

    @Test
    void 전부_4점_미만이면_빈_결과() {
        assertThat(reranker("[1, 2, 3]").rerank("q", List.of(hit(1), hit(2), hit(3)), 2)).isEmpty();
    }

    @Test
    void 후보가_topK_이하면_LLM을_부르지_않는다() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        LlmReranker r = new LlmReranker(builder, "x");
        assertThat(r.rerank("q", List.of(hit(1), hit(2)), 3)).hasSize(2);
        verifyNoInteractions(builder);
    }

    @Test
    void 파싱_실패나_예외면_원본_순서_topK() {
        assertThat(reranker("oops").rerank("q", List.of(hit(1), hit(2), hit(3)), 2)).extracting(ChunkHit::id).containsExactly(1L, 2L);
        assertThat(reranker(null).rerank("q", List.of(hit(1), hit(2), hit(3)), 2)).extracting(ChunkHit::id).containsExactly(1L, 2L);
    }

    @Test
    void 입력은_40건까지만() {
        List<ChunkHit> many = java.util.stream.LongStream.rangeClosed(1, 60).mapToObj(this::hit).toList();
        StringBuilder scores = new StringBuilder("[");
        for (int i = 0; i < SearchPolicy.RERANK_MAX_DOCS; i++) scores.append(i > 0 ? "," : "").append(5);
        scores.append("]");
        assertThat(reranker(scores.toString()).rerank("q", many, 5)).hasSize(5);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.search.*Rerank*"`
Expected: FAIL

- [ ] **Step 4: 구현**

`RerankScoreParser.java`:
```java
package io.saife.evidence.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 리랭크 응답 → 점수 배열. 길이가 문서 수와 다르면 null(폴백). Inufleet은 int[] 경로에서 길이를 안 봐 꼬리가 잘렸다 */
public final class RerankScoreParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private RerankScoreParser() {}

    public static int[] parse(String content, int expected) {
        if (content == null) return null;
        int s = content.indexOf('['), e = content.lastIndexOf(']');
        if (s < 0 || e <= s) return null;
        try {
            JsonNode root = MAPPER.readTree(content.substring(s, e + 1));
            if (!root.isArray() || root.size() != expected) return null;
            int[] out = new int[expected];
            for (int i = 0; i < expected; i++) {
                JsonNode n = root.get(i);
                if (n.isNumber()) out[i] = n.intValue();
                else if (n.isObject()) {
                    Integer v = null;
                    var it = n.fields();
                    while (it.hasNext()) { var f = it.next(); if (f.getValue().isNumber()) { v = f.getValue().intValue(); break; } }
                    if (v == null) return null;
                    out[i] = v;
                } else return null;
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }
}
```

`LlmReranker.java`:
```java
package io.saife.evidence.search;

import io.saife.evidence.chunk.ContextualPrefix;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Gemini Flash 리랭크(Inufleet LlmRerankPostProcessor 이식).
 * thinkingBudget(0)이 아니면 Flash가 JSON 지시를 무시한다(실측). 실패는 원본 순서 topK.
 */
@Slf4j
@Component
public class LlmReranker {
    private final ChatClient.Builder builder;
    private final String template;
    @Value("${spring.ai.google.genai.chat.options.model:gemini-3.8-flash}")
    private String model = "gemini-3.8-flash";

    @Autowired
    public LlmReranker(ChatClient.Builder builder, @Value("classpath:prompts/evidence-rerank.txt") Resource prompt) throws IOException {
        this(builder, prompt.getContentAsString(StandardCharsets.UTF_8));
    }

    LlmReranker(ChatClient.Builder builder, String template) { this.builder = builder; this.template = template; }

    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;
        List<ChunkHit> docs = candidates.subList(0, Math.min(candidates.size(), SearchPolicy.RERANK_MAX_DOCS));
        try {
            String content = builder.build().prompt().user(buildPrompt(query, docs))
                    .options(GoogleGenAiChatOptions.builder().model(model).temperature(0.0)
                            .maxOutputTokens(500).responseMimeType("application/json").thinkingBudget(0).build())
                    .call().content();
            int[] scores = RerankScoreParser.parse(content, docs.size());
            if (scores == null) { log.warn("[RERANK] 점수 파싱 실패 — 원본 순서"); return fallback(docs, topK); }
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < scores.length; i++) pairs.add(new int[]{scores[i], i});
            pairs.sort(Comparator.comparingInt((int[] p) -> p[0]).reversed());
            return pairs.stream().filter(p -> p[0] >= SearchPolicy.RERANK_MIN_SCORE).limit(topK)
                    .map(p -> docs.get(p[1]).withScore(p[0] / 10.0)).toList();
        } catch (Exception e) {
            log.warn("[RERANK] 호출 실패 — 원본 순서: {}", e.getMessage());
            return fallback(docs, topK);
        }
    }

    private List<ChunkHit> fallback(List<ChunkHit> docs, int topK) { return docs.subList(0, Math.min(docs.size(), topK)); }

    private String buildPrompt(String query, List<ChunkHit> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            String text = docs.get(i).title() + "\n" + docs.get(i).text();   // 맥락 프리픽스는 리랭커에 유용해 유지
            if (text.length() > SearchPolicy.RERANK_MAX_CHARS) text = text.substring(0, SearchPolicy.RERANK_MAX_CHARS);
            sb.append("[문서 ").append(i + 1).append("]\n").append(text).append("\n\n");
        }
        return template.replace("{query}", query).replace("{documents}", sb.toString().trim());
    }
}
```
(`ContextualPrefix` import는 사용하지 않으면 제거한다.)

- [ ] **Step 5: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.search.*Rerank*"`
Expected: PASS 10건

```bash
git add backend/src/main/resources/prompts/evidence-rerank.txt backend/src/main/java/io/saife/evidence/search backend/src/test/java/io/saife/evidence/search
git commit -m "feat(evidence): Flash 리랭커 — 0~10점, 4점 미만 제외, 길이 검사 폴백, 입력 40건 상한"
```

---

### Task 5: `QueryEmbedder` + `Evidence` 레코드

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/search/QueryEmbedder.java`
- Create: `backend/src/main/java/io/saife/evidence/search/GeminiQueryEmbedder.java`
- Create: `backend/src/main/java/io/saife/evidence/Evidence.java`
- Test: `backend/src/test/java/io/saife/evidence/search/GeminiQueryEmbedderTest.java`

**Interfaces:**
- Produces:
  - `interface QueryEmbedder { Optional<float[]> embed(String text); boolean available(); }`
  - `GeminiQueryEmbedder(EmbeddingModel, DemoModeConfig, LiveOrCache)` — 데모 모드면 `available()=false`. 8초 타임아웃·회로는 `LiveOrCache.fetch("gemini-embedding", ...)`로.
  - `record Evidence(int no, EvidenceKind kind, Long refId, String refKey, String title, String snippet, String sourceUrl, String mediaUrl, String thumbnailUrl, Origin origin, double score, OffsetDateTime fetchedAt, Map<String,Object> meta)` + `Evidence withNo(int)`

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saife.common.config.DemoModeConfig;
import io.saife.evidence.live.LiveOrCache;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class GeminiQueryEmbedderTest {
    @Test
    void 데모_모드면_available_false이고_호출_안_함() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        when(cfg.isDemoMode()).thenReturn(true);
        GeminiQueryEmbedder e = new GeminiQueryEmbedder(model, cfg, new LiveOrCache(Duration.ofSeconds(1), 3, Duration.ofSeconds(60)));
        assertThat(e.available()).isFalse();
        assertThat(e.embed("q")).isEmpty();
        verifyNoInteractions(model);
    }

    @Test
    void 성공하면_벡터() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(String.class))).thenReturn(new float[]{0.1f, 0.2f});
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        GeminiQueryEmbedder e = new GeminiQueryEmbedder(model, cfg, new LiveOrCache(Duration.ofSeconds(1), 3, Duration.ofSeconds(60)));
        assertThat(e.embed("q")).contains(new float[]{0.1f, 0.2f});
    }

    @Test
    void 실패하면_empty() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(String.class))).thenThrow(new RuntimeException("429"));
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        GeminiQueryEmbedder e = new GeminiQueryEmbedder(model, cfg, new LiveOrCache(Duration.ofSeconds(1), 3, Duration.ofSeconds(60)));
        assertThat(e.embed("q")).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.GeminiQueryEmbedderTest`
Expected: FAIL

- [ ] **Step 3: 구현**

`QueryEmbedder.java`:
```java
package io.saife.evidence.search;
import java.util.Optional;
/** 질의 임베딩. 테스트와 데모 모드에서 갈아끼운다 */
public interface QueryEmbedder {
    Optional<float[]> embed(String text);
    boolean available();
}
```

`GeminiQueryEmbedder.java`:
```java
package io.saife.evidence.search;

import io.saife.common.config.DemoModeConfig;
import io.saife.evidence.live.Fetched;
import io.saife.evidence.live.LiveOrCache;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/** gemini-embedding-2(768). 타임아웃·회로는 LiveOrCache가 맡는다. 캐시는 없다(질의는 매번 새것) */
@Component
@RequiredArgsConstructor
public class GeminiQueryEmbedder implements QueryEmbedder {
    private static final String HOST = "gemini-embedding";
    private final EmbeddingModel embeddingModel;
    private final DemoModeConfig demoModeConfig;
    private final LiveOrCache liveOrCache;

    @Override
    public boolean available() { return !demoModeConfig.isDemoMode() && !liveOrCache.isOpen(HOST); }

    @Override
    public Optional<float[]> embed(String text) {
        if (!available() || text == null || text.isBlank()) return Optional.empty();
        Fetched<float[]> f = liveOrCache.fetch(HOST, true, () -> embeddingModel.embed(text), Optional::empty, v -> {});
        return Optional.ofNullable(f.value());
    }
}
```

`Evidence.java`:
```java
package io.saife.evidence;

import io.saife.evidence.live.Origin;
import java.time.OffsetDateTime;
import java.util.Map;

/** 근거 카드 한 장. 프론트 types/evidence.ts와 1:1 */
public record Evidence(int no, EvidenceKind kind, Long refId, String refKey, String title, String snippet,
                       String sourceUrl, String mediaUrl, String thumbnailUrl, Origin origin, double score,
                       OffsetDateTime fetchedAt, Map<String, Object> meta) {
    public Evidence withNo(int n) {
        return new Evidence(n, kind, refId, refKey, title, snippet, sourceUrl, mediaUrl, thumbnailUrl, origin, score, fetchedAt, meta);
    }
    /** 원장 중복 판정 키 */
    public String identity() { return kind + ":" + refKey; }
}
```

`application.yml`의 `spring.ai.google.genai.embedding.text.options.dimensions`를 `1536` → `768`로 바꾼다(`spring.ai.vectorstore.pgvector.dimensions`도 768).

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.GeminiQueryEmbedderTest`
Expected: PASS 3건

```bash
git add backend/src/main/java/io/saife/evidence backend/src/main/resources/application.yml backend/src/test/java/io/saife/evidence/search
git commit -m "feat(evidence): 질의 임베더(768차원, 회로 차단)와 Evidence 카드 레코드"
```

---

### Task 6: `EvidenceSearchService` — 파이프라인 조립

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/search/SearchRequest.java`
- Create: `backend/src/main/java/io/saife/evidence/search/EvidenceMapper.java`
- Create: `backend/src/main/java/io/saife/evidence/search/EvidenceSearchService.java`
- Test: `backend/src/test/java/io/saife/evidence/search/EvidenceSearchServiceTest.java`

**Interfaces:**
- Produces:
  - `record SearchRequest(String query, Set<EvidenceKind> kinds, AccidentType accidentType, String business, int k, boolean rerank)` + `static SearchRequest cases(query, axis, business, k)`, `guides(query, k)`
  - `EvidenceMapper.toEvidence(ChunkHit, Origin) → Evidence` (sourceUrl/mediaUrl/thumbnailUrl 규칙: CASE → `/api/media/case/{refId}/photo`(hasImage일 때), `?w=320`; GUIDE → `/api/media/guide/{guideNo}.pdf`, sourceUrl=`meta.fileDownloadUrl`가 없으면 mediaUrl; LAW → sourceUrl=`meta.sourceUrl`)
  - `EvidenceSearchService.search(SearchRequest) → List<Evidence>` (no=0)

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import java.util.*;
import org.junit.jupiter.api.Test;

class EvidenceSearchServiceTest {
    private ChunkHit caseHit(long id, String axis, String business, boolean image, double score) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("accidentType", axis); meta.put("business", business); meta.put("hasImage", image);
        return new ChunkHit(id, EvidenceKind.CASE_FATALITY, id, "FATALITY:" + id, "child", null, null, "[추락] 사례 " + id, "[맥락]\n본문 " + id, meta, score);
    }

    private EvidenceChunkRepository repo = mock(EvidenceChunkRepository.class);
    private QueryEmbedder embedder = mock(QueryEmbedder.class);
    private LlmReranker reranker = mock(LlmReranker.class);
    private ParentChunkExpander expander = new ParentChunkExpander(repo);

    private EvidenceSearchService service() { return new EvidenceSearchService(repo, embedder, expander, reranker); }

    @Test
    void 키_없으면_키워드_폴백() {
        when(embedder.available()).thenReturn(false);
        when(repo.keywordSearch(anyString(), anySet(), any(), anyInt())).thenReturn(List.of(caseHit(1, "FALL", "제조업", true, 0.5)));
        List<Evidence> out = service().search(SearchRequest.cases("사다리 추락", AccidentType.FALL, "제조업", 3));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).origin()).isEqualTo(Origin.KEYWORD_FALLBACK);
        verify(embedder, never()).embed(anyString());
        verifyNoInteractions(reranker);
        verify(repo, never()).vectorSearch(any(), anySet(), any(), anyInt());
    }

    @Test
    void 벡터_키워드_RRF_리랭크_보정_순으로_흐른다() {
        when(embedder.available()).thenReturn(true);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{1f}));
        when(repo.vectorSearch(any(), anySet(), any(), anyInt())).thenReturn(List.of(caseHit(1, "FALL", "제조업", false, 0.9), caseHit(2, "CAUGHT", "건설업", true, 0.8)));
        when(repo.keywordSearch(anyString(), anySet(), any(), anyInt())).thenReturn(List.of(caseHit(2, "CAUGHT", "건설업", true, 0.3)));
        when(repo.findParents(anySet())).thenReturn(Map.of());
        when(reranker.rerank(anyString(), anyList(), eq(3))).thenAnswer(inv -> {
            List<ChunkHit> in = inv.getArgument(1);
            return in.stream().map(h -> h.withScore(0.7)).toList();   // 동점 → 보정이 순서를 정한다
        });
        List<Evidence> out = service().search(SearchRequest.cases("사다리", AccidentType.FALL, "제조업", 3));
        assertThat(out).hasSize(2);
        // 1: 같은 축 +0.05, 제조업 +0.03 = 0.78 / 2: 사진 +0.02 = 0.72
        assertThat(out.get(0).refId()).isEqualTo(1L);
        assertThat(out.get(0).origin()).isEqualTo(Origin.CACHE);
        assertThat(out.get(0).snippet()).isEqualTo("본문 1");   // 맥락 프리픽스 제거
        assertThat(out.get(1).thumbnailUrl()).isEqualTo("/api/media/case/2/photo?w=320");
        assertThat(out.get(0).mediaUrl()).isNull();
    }

    @Test
    void 지침은_같은_guideNo가_한_번만() {
        when(embedder.available()).thenReturn(false);
        Map<String, Object> m = Map.of("guideNo", "G-1", "fileDownloadUrl", "https://portal.kosha.or.kr/f/1");
        ChunkHit a = new ChunkHit(1, EvidenceKind.GUIDE, 1, "G-1#c1", "child", null, "s", "[KOSHA GUIDE G-1] x", "t1", m, 0.5);
        ChunkHit b = new ChunkHit(2, EvidenceKind.GUIDE, 1, "G-1#c2", "child", null, "s", "[KOSHA GUIDE G-1] x", "t2", m, 0.4);
        when(repo.keywordSearch(anyString(), anySet(), any(), anyInt())).thenReturn(List.of(a, b));
        List<Evidence> out = service().search(SearchRequest.guides("사다리", 3));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).mediaUrl()).isEqualTo("/api/media/guide/G-1.pdf");
        assertThat(out.get(0).sourceUrl()).isEqualTo("https://portal.kosha.or.kr/f/1");
    }

    @Test
    void 빈_질의는_빈_결과() {
        assertThat(service().search(SearchRequest.cases("  ", null, null, 3))).isEmpty();
        verifyNoInteractions(repo);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.search.EvidenceSearchServiceTest`
Expected: FAIL

- [ ] **Step 3: 구현**

`SearchRequest.java`:
```java
package io.saife.evidence.search;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.EvidenceKind;
import java.util.Set;

public record SearchRequest(String query, Set<EvidenceKind> kinds, AccidentType accidentType, String business, int k, boolean rerank) {
    public static SearchRequest cases(String query, AccidentType axis, String business, int k) {
        return new SearchRequest(query, Set.of(EvidenceKind.CASE_FATALITY, EvidenceKind.CASE_DISASTER), axis, business, k, true);
    }
    public static SearchRequest guides(String query, int k) {
        return new SearchRequest(query, Set.of(EvidenceKind.GUIDE), null, null, k, true);
    }
    public static SearchRequest laws(String query, int k) {
        return new SearchRequest(query, Set.of(EvidenceKind.LAW), null, null, k, true);
    }
    public SearchRequest withoutRerank() { return new SearchRequest(query, kinds, accidentType, business, k, false); }
}
```

`EvidenceMapper.java`:
```java
package io.saife.evidence.search;

import io.saife.evidence.Evidence;
import io.saife.evidence.chunk.ContextualPrefix;
import io.saife.evidence.live.Origin;
import java.time.OffsetDateTime;
import java.util.Map;

/** ChunkHit → 카드. URL 규칙이 여기 한 곳에 있다 */
public final class EvidenceMapper {
    private EvidenceMapper() {}

    public static Evidence toEvidence(ChunkHit h, Origin origin) {
        Map<String, Object> m = h.metadata();
        String body = ContextualPrefix.strip(h.text());
        String snippet = body.length() > SearchPolicy.SNIPPET_CHARS ? body.substring(0, SearchPolicy.SNIPPET_CHARS) + "…" : body;
        String source = null, media = null, thumb = null;
        switch (h.kind()) {
            case CASE_FATALITY, CASE_DISASTER -> {
                boolean hasImage = Boolean.TRUE.equals(m.get("hasImage"));
                if (hasImage) { media = "/api/media/case/" + h.refId() + "/photo"; thumb = media + "?w=320"; }
                source = str(m.get("sourceUrl"));
            }
            case GUIDE -> {
                String guideNo = str(m.get("guideNo"));
                media = guideNo == null ? null : "/api/media/guide/" + guideNo + ".pdf";
                source = str(m.get("fileDownloadUrl")) != null ? str(m.get("fileDownloadUrl")) : media;
            }
            case LAW -> source = str(m.get("sourceUrl"));
            case MSDS -> source = str(m.get("sourceUrl"));
        }
        return new Evidence(0, h.kind(), h.refId(), h.refKey(), h.title(), snippet, source, media, thumb, origin,
                Math.round(h.score() * 1000) / 1000.0, OffsetDateTime.now(), m);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
```

`EvidenceSearchService.java`:
```java
package io.saife.evidence.search;

import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 하이브리드 검색 파이프라인(Inufleet AgenticRagService 이식).
 * 벡터 k×4 ‖ 키워드 k×4 → RRF → Parent 확장 → 리랭크 → 도메인 보정 → 카드.
 * 키가 없으면 키워드만으로 같은 형태의 결과(KEYWORD_FALLBACK).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceSearchService {
    private final EvidenceChunkRepository repository;
    private final QueryEmbedder embedder;
    private final ParentChunkExpander expander;
    private final LlmReranker reranker;

    public List<Evidence> search(SearchRequest req) {
        if (req.query() == null || req.query().isBlank() || req.k() <= 0) return List.of();
        int wide = req.k() * SearchPolicy.CANDIDATE_MULTIPLIER;
        String axis = req.accidentType() == null ? null : req.accidentType().name();
        String tsquery = TsQueryBuilder.build(req.query());

        Optional<float[]> q = embedder.available() ? embedder.embed(req.query()) : Optional.empty();
        List<ChunkHit> vec = q.map(v -> repository.vectorSearch(v, req.kinds(), axis, wide)).orElse(List.of());
        List<ChunkHit> kw = repository.keywordSearch(tsquery, req.kinds(), axis, wide);
        Origin origin = q.isPresent() ? Origin.CACHE : Origin.KEYWORD_FALLBACK;

        List<ChunkHit> fused = HybridRrf.fuse(vec, kw, wide);
        if (fused.isEmpty()) return List.of();
        List<ChunkHit> expanded = expander.expand(fused);
        List<ChunkHit> ranked = (req.rerank() && q.isPresent()) ? reranker.rerank(req.query(), expanded, req.k())
                : expanded.subList(0, Math.min(expanded.size(), req.k() * 2));
        return boostAndDedupe(ranked, req).stream().limit(req.k()).map(h -> EvidenceMapper.toEvidence(h, origin)).toList();
    }

    /** 같은 축 +0.05, 제조업 +0.03, 사진 +0.02(사례만). 같은 지침(guideNo)은 1건 */
    private List<ChunkHit> boostAndDedupe(List<ChunkHit> hits, SearchRequest req) {
        List<ChunkHit> boosted = new ArrayList<>();
        Set<String> seenGuide = new HashSet<>();
        for (ChunkHit h : hits) {
            Map<String, Object> m = h.metadata();
            if (h.kind() == EvidenceKind.GUIDE) {
                String g = String.valueOf(m.get("guideNo"));
                if (!seenGuide.add(g)) continue;
            }
            double s = h.score();
            if (req.accidentType() != null && req.accidentType().name().equals(m.get("accidentType"))) s += SearchPolicy.BOOST_SAME_AXIS;
            if ("제조업".equals(m.get("business"))) s += SearchPolicy.BOOST_MANUFACTURING;
            if (h.kind().isCase() && Boolean.TRUE.equals(m.get("hasImage"))) s += SearchPolicy.BOOST_HAS_IMAGE;
            boosted.add(h.withScore(s));
        }
        boosted.sort(Comparator.comparingDouble(ChunkHit::score).reversed());
        return boosted;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.search.*"`
Expected: PASS 전부 (IT 1건은 5433 DB 필요)

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/io/saife/evidence backend/src/test/java/io/saife/evidence/search
git commit -m "feat(evidence): EvidenceSearchService — 하이브리드 RRF·Parent 확장·리랭크·도메인 보정·키워드 폴백"
```

---

## Self-Review

- 스펙 §5 ①~⑥ 전부 Task 1~6에 대응. §5.6 함정: 생성 컬럼 tsv(A1 V9), searchable 불리언(A1), OR 매칭(Task 1), parent 실패 시 child 유지(Task 3), 리랭크 40건·thinkingBudget(0)·길이 검사(Task 4), 데모 모드 폴백(Task 5·6).
- 플레이스홀더 없음. `Evidence`, `EvidenceSearchService.search`, `SearchRequest.cases/guides/laws`는 B 계획이 그대로 호출한다.
- Review Focus 5건 모두 테스트 존재.
