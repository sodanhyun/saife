package io.saife.evidence.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.EvidenceKind;
import java.sql.ResultSet;
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
            select id, kind, ref_id, ref_key, chunk_level, parent_id, section_title, title, text, metadata::text as metadata,
                   updated_at
            """;

    /**
     * 업종 필터(최종 리뷰 F7). {@code business}가 null이면 필터 없음. 값이 있으면 그 업종이거나
     * <b>업종 필드 자체가 없는</b> 행을 남긴다 — 사진이 있는 유일한 종류인 사고사망(1040, CASE_FATALITY)은
     * 원본에 업종 필드가 없어 전부 null이다(R50/R51). null까지 거르면 사진 카드가 사라진다.
     * 지침·조문 청크도 업종 필드가 없으므로 같은 규칙으로 그대로 통과한다.
     */
    private static final String BUSINESS_FILTER =
            "and (?::text is null or metadata->>'business' is null or metadata->>'business' = ?::text) ";

    private static final String PHOTO_FILTER = "and (metadata->>'hasImage')::boolean ";

    public List<ChunkHit> vectorSearch(float[] q, Set<EvidenceKind> kinds, String axis, int limit) {
        return vectorSearch(q, kinds, axis, null, limit);
    }

    public List<ChunkHit> vectorSearch(float[] q, Set<EvidenceKind> kinds, String axis, String business, int limit) {
        return vectorSearch(q, kinds, axis, business, false, limit);
    }

    /** photoOnly: 사진이 있는 사례(metadata.hasImage)만 */
    public List<ChunkHit> vectorSearch(float[] q, Set<EvidenceKind> kinds, String axis, String business, boolean photoOnly, int limit) {
        try {
            String v = toVectorLiteral(q);
            return jdbc.query(SELECT + ", 1 - (embedding <=> ?::vector) as score from evidence_chunk "
                            + "where searchable and kind = any(?) and (?::text is null or metadata->>'accidentType' = ?::text) "
                            + BUSINESS_FILTER + (photoOnly ? PHOTO_FILTER : "")
                            + "and embedding is not null and 1 - (embedding <=> ?::vector) >= ? "
                            + "order by embedding <=> ?::vector limit ?",
                    mapper(), v, kindsArray(kinds), axis, axis, business, business, v, SearchPolicy.SIMILARITY_THRESHOLD, v, limit);
        } catch (Exception e) {
            log.warn("[SEARCH] 벡터 검색 실패 — 빈 결과: {}", e.getMessage());
            return List.of();
        }
    }

    public List<ChunkHit> keywordSearch(String tsquery, Set<EvidenceKind> kinds, String axis, int limit) {
        return keywordSearch(tsquery, kinds, axis, null, limit);
    }

    public List<ChunkHit> keywordSearch(String tsquery, Set<EvidenceKind> kinds, String axis, String business, int limit) {
        return keywordSearch(tsquery, kinds, axis, business, false, limit);
    }

    public List<ChunkHit> keywordSearch(String tsquery, Set<EvidenceKind> kinds, String axis, String business, boolean photoOnly, int limit) {
        if (tsquery == null || tsquery.isBlank()) return List.of();
        try {
            return jdbc.query(SELECT + ", ts_rank_cd(tsv, to_tsquery('simple', ?)) as score from evidence_chunk "
                            + "where searchable and kind = any(?) and (?::text is null or metadata->>'accidentType' = ?::text) "
                            + BUSINESS_FILTER + (photoOnly ? PHOTO_FILTER : "")
                            + "and tsv @@ to_tsquery('simple', ?) order by score desc limit ?",
                    mapper(), tsquery, kindsArray(kinds), axis, axis, business, business, tsquery, limit);
        } catch (Exception e) {
            log.warn("[SEARCH] 키워드 검색 실패 — 빈 결과: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<Long, ChunkHit> findParents(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            // R7: Long[]을 마지막 단일 vararg로 넘기면 자바가 배열 자체를 펼쳐 개별 파라미터로
            // 흩뿌린다(단건일 때만 우연히 자리 수가 맞아 "any(bigint)"로 잘못 바인딩되어 실패).
            // PreparedStatementSetter + createArrayOf로 bigint[] 배열 하나를 명시적으로 바인딩한다.
            List<ChunkHit> rows = jdbc.query(SELECT + ", 0 as score from evidence_chunk where id = any(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids.toArray())),
                    mapper());
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

    /**
     * 시드 재적재용 ref_id 조회. 시드 파일에는 ref_id를 담지 않는다 — 재적재 시 원본
     * 테이블의 auto-increment id가 그대로 재현된다는 보장이 없기 때문이다. {@code EvidenceSeedLoader}가
     * kind별로 실제 참조 테이블을 한 번 읽어 refKey → id 맵을 만들 때 쓴다.
     */
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

    public void deleteByKind(EvidenceKind kind) {
        jdbc.update("delete from evidence_chunk where kind = ?", kind.name());
    }

    /**
     * 행 단위 upsert({@code INSERT … ON CONFLICT … RETURNING id}를 한 행씩 실행한다 — JDBC 배치가 아니다).
     * 호출자가 넘긴 리스트 크기(시드 로더 500건·색인 100건)가 곧 트랜잭션 단위일 뿐이다.
     * parentId는 호출자가 먼저 parent를 넣고 id를 받아 채운다.
     */
    public List<Long> insertBatch(List<ChunkRow> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (ChunkRow r : rows) {
            // A4 hotfix H1 (ruling R33): PdfTextExtractor가 GUIDE 본문의 제어문자를 걸러내지만,
            // 다른 kind(CASE_*, LAW, MSDS)의 원문에도 같은 문제가 재발할 수 있다. Postgres는
            // UTF8 텍스트에 NUL(0x00)을 절대 허용하지 않아 배치 insert 전체가 예외로 죽으므로,
            // 인덱스 경로의 마지막 방어선으로 여기서도 NUL만 한 번 더 걷어낸다.
            String text = stripNul(r.text());
            String title = stripNul(r.title());
            String sectionTitle = stripNul(r.sectionTitle());
            if (!Objects.equals(text, r.text()) || !Objects.equals(title, r.title()) || !Objects.equals(sectionTitle, r.sectionTitle())) {
                log.debug("[CHUNK] refKey={}에서 NUL 바이트 제거 (kind={})", r.refKey(), r.kind());
            }
            Long id = jdbc.queryForObject("""
                    insert into evidence_chunk (kind, ref_id, ref_key, chunk_level, parent_id, searchable, section_title, title, text, metadata, embedding)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
                    on conflict (kind, ref_key) do update set text = excluded.text, title = excluded.title, metadata = excluded.metadata,
                        embedding = excluded.embedding, parent_id = excluded.parent_id, searchable = excluded.searchable, updated_at = now()
                    returning id
                    """, Long.class,
                    r.kind().name(), r.refId(), r.refKey(), r.chunkLevel(), r.parentId(), r.searchable(), sectionTitle,
                    title, text, toJson(r.metadata()), r.embedding() == null ? null : toVectorLiteral(r.embedding()));
            ids.add(id);
        }
        return ids;
    }

    private static String stripNul(String s) {
        return s == null ? null : s.indexOf('\u0000') < 0 ? s : s.replace("\u0000", "");
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
                rs.getString("title"), rs.getString("text"), parse(rs.getString("metadata")), rs.getDouble("score"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private Map<String, Object> parse(String json) {
        try { return json == null ? Map.of() : objectMapper.readValue(json, MAP); } catch (Exception e) { return Map.of(); }
    }
}
