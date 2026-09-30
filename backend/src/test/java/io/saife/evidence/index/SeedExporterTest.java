package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.evidence.search.EvidenceChunkRepository.ChunkRow;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 5433 DB(테스트 프로필)를 대상으로 실제 pgvector 텍스트 표현을 파싱해 base64로
 * 되감는 경로를 검증한다. {@code @Transactional}이라 끝나면 롤백되고,
 * {@code src/main/resources/seed}에는 절대 쓰지 않는다({@code @TempDir}만 쓴다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SeedExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private SeedExporter exporter;
    @Autowired
    private EvidenceChunkRepository chunkRepo;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 다섯_파일_행수가_실제_테이블과_일치하고_parent가_child보다_먼저_온다(@TempDir Path dir) throws Exception {
        float[] childVector = new float[768];
        childVector[0] = 0.5f;
        childVector[767] = -1.25f;

        List<Long> parentIds = chunkRepo.insertBatch(List.of(
                new ChunkRow(EvidenceKind.LAW, 1, "EXPORT#p1", "parent", null, false, "s", "부모", "부모 텍스트", Map.of(), null)));
        chunkRepo.insertBatch(List.of(
                new ChunkRow(EvidenceKind.LAW, 1, "EXPORT#c1", "child", parentIds.get(0), true, "s", "자식",
                        "자식 텍스트", Map.of("axis", "FALL"), childVector)));

        long caseCount = count("public_case");
        long guideCount = count("kosha_guide");
        long lawCount = count("law_article");
        long msdsCount = count("msds_cache");
        long chunkCount = count("evidence_chunk");

        Map<String, Integer> report = exporter.exportAll(dir);

        assertThat(report.get("public_case.jsonl.gz")).isEqualTo((int) caseCount);
        assertThat(report.get("kosha_guide.jsonl.gz")).isEqualTo((int) guideCount);
        assertThat(report.get("law_article.jsonl.gz")).isEqualTo((int) lawCount);
        assertThat(report.get("msds_cache.jsonl.gz")).isEqualTo((int) msdsCount);
        assertThat(report.get("evidence_chunk.jsonl.gz")).isEqualTo((int) chunkCount);

        List<JsonNode> lines = readJsonl(dir.resolve("evidence_chunk.jsonl.gz"));
        int parentIdx = indexOfRefKey(lines, "EXPORT#p1");
        int childIdx = indexOfRefKey(lines, "EXPORT#c1");
        assertThat(parentIdx).isGreaterThanOrEqualTo(0);
        assertThat(childIdx).isGreaterThanOrEqualTo(0);
        assertThat(parentIdx).isLessThan(childIdx);   // parent가 child보다 먼저 온다

        JsonNode parentLine = lines.get(parentIdx);
        JsonNode childLine = lines.get(childIdx);
        assertThat(parentLine.get("chunkLevel").asText()).isEqualTo("parent");
        assertThat(parentLine.get("embedding").isNull()).isTrue();
        assertThat(childLine.get("parentRefKey").asText()).isEqualTo("EXPORT#p1");

        // A4 hotfix H2: SeedExporter가 이제 Q8 양자화로 내보내므로 비트 단위 일치 대신
        // 양자화 오차(성분당 <= (max-min)/255) 이내에서 비교한다. childVector는 0(기본값)이
        // 섞여 있어 min=-1.25, max=0.5 → 허용 오차 ≈ (0.5-(-1.25))/255 ≈ 0.00686
        float[] decoded = VectorCodec.decode(childLine.get("embedding").asText());
        assertThat(decoded[0]).isCloseTo(0.5f, within(0.01f));
        assertThat(decoded[767]).isCloseTo(-1.25f, within(0.01f));
    }

    /**
     * F2 회귀 테스트: msds_cache 조회에서 예외를 강제해 exportAll이 그 시점에서 중단되게 만든다.
     * 요구사항: (1) msds_cache.jsonl.gz.tmp가 남지 않아야 한다 (2) msds_cache.jsonl.gz(대상 파일)
     * 자체도 생기지 않아야 한다 (3) 그 전에 이미 완료된 public_case·kosha_guide·law_article은
     * 원자적 이동이 끝났으므로 그대로 남아 있어야 한다 — 파일별 원자성이지 전부-아니면-전무가 아니다.
     */
    @Test
    void 도중에_실패하면_임시파일이_남지_않고_대상파일도_생기지_않는다(@TempDir Path dir) {
        JdbcTemplate failingJdbc = spy(jdbc);
        doThrow(new RuntimeException("의도된 실패 — msds_cache 조회"))
                .when(failingJdbc).query(contains("from msds_cache"), any(RowCallbackHandler.class));
        SeedExporter failingExporter = new SeedExporter(failingJdbc, objectMapper);

        assertThatThrownBy(() -> failingExporter.exportAll(dir))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("의도된 실패");

        assertThat(Files.exists(dir.resolve("public_case.jsonl.gz"))).isTrue();
        assertThat(Files.exists(dir.resolve("kosha_guide.jsonl.gz"))).isTrue();
        assertThat(Files.exists(dir.resolve("law_article.jsonl.gz"))).isTrue();

        assertThat(Files.exists(dir.resolve("msds_cache.jsonl.gz"))).isFalse();
        assertThat(Files.exists(dir.resolve("msds_cache.jsonl.gz.tmp"))).isFalse();
        assertThat(Files.exists(dir.resolve("evidence_chunk.jsonl.gz"))).isFalse();
        assertThat(Files.exists(dir.resolve("evidence_chunk.jsonl.gz.tmp"))).isFalse();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }

    private int indexOfRefKey(List<JsonNode> lines, String refKey) {
        for (int i = 0; i < lines.size(); i++) {
            if (refKey.equals(lines.get(i).path("refKey").asText())) {
                return i;
            }
        }
        return -1;
    }

    private List<JsonNode> readJsonl(Path gz) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(Files.newInputStream(gz)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    out.add(MAPPER.readTree(line));
                }
            }
        }
        return out;
    }
}
