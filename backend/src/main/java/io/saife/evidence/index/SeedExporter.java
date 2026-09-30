package io.saife.evidence.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.live.LawUrls;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * DB → seed/*.jsonl.gz. 심사위원이 API 키 없이 앱을 띄울 수 있게 다섯 캐시 테이블을 그대로 내보낸다.
 *
 * <p>산출물은 {@code backend/src/main/resources/seed/}에 두고 커밋한다 —
 * {@link io.saife.publicapi.service.PublicCacheSeedLoader}와 {@code EvidenceSeedLoader}(Task 3)가
 * classpath({@code seed/*.jsonl.gz})에서 그대로 읽으므로 파일명·필드명을 바꾸면 로더도 같이 고쳐야 한다.
 *
 * <p>{@code evidence_chunk}는 <b>parent가 항상 child보다 먼저</b> 오도록 정렬해서 쓴다.
 * 로더가 parent를 먼저 넣고 그 id를 {@code parentRefKey} → id로 맵핑한 뒤 child를 넣는
 * 2-pass 방식이지만(순서를 믿지 않는다), 그래도 순서를 맞춰 두면 사람이 파일을 열어봤을 때도
 * 구조가 바로 읽힌다.
 *
 * <p><b>파일 단위 원자성.</b> 각 파일은 {@code <name>.tmp}로 먼저 쓰고, gzip 스트림이
 * 정상적으로 닫힌 뒤에만 원본 위치로 옮긴다. 도중에 DB 조회나 쓰기가 실패하면 tmp 파일을
 * 지우고 예외를 그대로 던진다 — 이미 완료된 앞선 파일들은 그대로 남는다(전부-아니면-전무가
 * 아니라 파일별 원자성이다). 5개 파일을 한 트랜잭션으로 묶지 않는 것과 같은 맥락이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeedExporter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** dir 아래 5개 파일을 만들고 파일명→행 수를 돌려준다 */
    public Map<String, Integer> exportAll(Path dir) throws IOException {
        Files.createDirectories(dir);
        log.info("[SEED] 내보내기 시작 dir={}", dir.toAbsolutePath());
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("public_case.jsonl.gz", dump(dir.resolve("public_case.jsonl.gz"),
                """
                select source, source_key as "sourceKey", business, keyword, contents,
                       accident_type as "accidentType", region, occurred_on::text as "occurredOn",
                       image_url as "imageUrl", source_url as "sourceUrl"
                from public_case order by id
                """));
        out.put("kosha_guide.jsonl.gz", dump(dir.resolve("kosha_guide.jsonl.gz"),
                """
                select guide_no as "guideNo", guide_name as "guideName",
                       announced_on::text as "announcedOn", file_download_url as "fileDownloadUrl"
                from kosha_guide order by id
                """));
        out.put("law_article.jsonl.gz", dump(dir.resolve("law_article.jsonl.gz"),
                """
                select law_id as "lawId", law_name as "lawName", article_no as "articleNo",
                       article_sub as "articleSub", paragraph_no as "paragraphNo", title, text,
                       effective_on::text as "effectiveOn", source_url as "sourceUrl"
                from law_article order by law_id, article_no, article_sub, paragraph_no
                """));
        out.put("msds_cache.jsonl.gz", dump(dir.resolve("msds_cache.jsonl.gz"),
                """
                select chem_id as "chemId", chem_name_kor as "chemNameKor", cas_no as "casNo",
                       un_no as "unNo", section_code as "sectionCode", item_code as "itemCode",
                       item_name as "itemName", item_detail as "itemDetail", pictograms
                from msds_cache order by id
                """));
        out.put("evidence_chunk.jsonl.gz", dumpChunks(dir.resolve("evidence_chunk.jsonl.gz")));
        int total = out.values().stream().mapToInt(Integer::intValue).sum();
        log.info("[SEED] 내보내기 완료 dir={} 파일={} 총행수={}", dir.toAbsolutePath(), out.size(), total);
        return out;
    }

    /** 컬럼 별칭이 곧 JSON 필드명이 되는 범용 덤프. 4개 테이블(public_case·kosha_guide·law_article·msds_cache)에 쓴다 */
    private int dump(Path target, String sql) throws IOException {
        Path tmp = tmpFor(target);
        int[] n = {0};
        try {
            try (Writer w = writer(tmp)) {
                jdbc.query(sql, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    try {
                        // 최종 리뷰 F1: 법제처 OC 자격증명이 동봉 시드로 다시 새지 않게 줄 단위로 거른다
                        w.write(LawUrls.stripOc(mapper.writeValueAsString(row)));
                        w.write('\n');
                        n[0]++;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            moveIntoPlace(tmp, target);
        } catch (RuntimeException | IOException e) {
            deleteQuietly(tmp);
            throw e;
        }
        log.info("[SEED] {} {}행", target.getFileName(), n[0]);
        return n[0];
    }

    /**
     * evidence_chunk는 self-join으로 parent의 ref_key를 함께 뽑고, 벡터는
     * {@code embedding::text}로 pgvector 텍스트 표현({@code "[0.1,0.2,...]"})을 받아 직접 파싱한다
     * (pgvector JDBC 타입에 기대지 않는다). parent는 항상 먼저 오도록 정렬한다.
     */
    private int dumpChunks(Path target) throws IOException {
        Path tmp = tmpFor(target);
        int[] n = {0};
        try {
            try (Writer w = writer(tmp)) {
                jdbc.query("""
                        select c.kind, c.ref_key as "refKey", c.chunk_level as "chunkLevel",
                               p.ref_key as "parentRefKey", c.searchable, c.section_title as "sectionTitle",
                               c.title, c.text, c.metadata::text as metadata, c.embedding::text as embedding
                        from evidence_chunk c left join evidence_chunk p on p.id = c.parent_id
                        order by (c.chunk_level = 'parent') desc, c.id
                        """, rs -> {
                    String refKey = rs.getString("refKey");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kind", rs.getString("kind"));
                    row.put("refKey", refKey);
                    row.put("chunkLevel", rs.getString("chunkLevel"));
                    row.put("parentRefKey", rs.getString("parentRefKey"));
                    row.put("searchable", rs.getBoolean("searchable"));
                    row.put("sectionTitle", rs.getString("sectionTitle"));
                    row.put("title", rs.getString("title"));
                    row.put("text", rs.getString("text"));
                    try {
                        row.put("metadata", mapper.readTree(rs.getString("metadata")));
                    } catch (Exception e) {
                        // 손상된 metadata는 흔치 않다(NOT NULL DEFAULT '{}'). warn으로 시끄럽게 하지 않고
                        // debug로만 남긴다 — 대량 데이터에서 반복될 수 있다
                        log.debug("[SEED] evidence_chunk 메타데이터 파싱 실패 — 빈 객체로 대체 refKey={}: {}", refKey, e.getMessage());
                        row.put("metadata", Map.of());
                    }
                    // A4 hotfix H2: 인덱스가 4만 청크로 커지며 float32 인코딩이 git 파일 한도를
                    // 넘어서, 시드 파일의 벡터만 Q8 양자화로 내보낸다 — 시드 크기 1/4.
                    // decode()가 바이트 길이로 자동 판별하므로 로더는 변경 없이 그대로 읽는다.
                    String emb = rs.getString("embedding");
                    row.put("embedding", emb == null ? null : VectorCodec.encodeQ8(parseVector(emb)));
                    try {
                        // 최종 리뷰 F1: 법제처 OC 자격증명이 동봉 시드로 다시 새지 않게 줄 단위로 거른다
                        w.write(LawUrls.stripOc(mapper.writeValueAsString(row)));
                        w.write('\n');
                        n[0]++;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            moveIntoPlace(tmp, target);
        } catch (RuntimeException | IOException e) {
            deleteQuietly(tmp);
            throw e;
        }
        log.info("[SEED] {} {}행", target.getFileName(), n[0]);
        return n[0];
    }

    /** pgvector 텍스트 표현 "[0.1,0.2,...]" → float[]. 대괄호를 벗기고 콤마로 쪼갠다 */
    static float[] parseVector(String literal) {
        String[] parts = literal.substring(1, literal.length() - 1).split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }

    private Writer writer(Path file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8));
    }

    /** 같은 디렉터리에 <파일명>.tmp 경로를 만든다 — 원자적 이동이 같은 파일시스템 안에서만 보장되므로 */
    private Path tmpFor(Path target) {
        return target.resolveSibling(target.getFileName().toString() + ".tmp");
    }

    /** ATOMIC_MOVE를 우선 시도하고, 파일시스템이 거부하면(예: 네트워크 드라이브) 일반 이동으로 물러선다 */
    private void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("[SEED] 원자적 이동 미지원 — 일반 이동으로 대체: {}", target, e);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 실패 정리용. 지우기 자체가 실패해도(예: 파일이 이미 없음) 원래 예외를 가리지 않는다 */
    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("[SEED] 임시 파일 정리 실패(무시): {}", p, e);
        }
    }
}
