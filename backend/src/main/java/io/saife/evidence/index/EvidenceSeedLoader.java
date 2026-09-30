package io.saife.evidence.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.live.LawUrls;
import io.saife.evidence.repository.LawArticleRepository;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.evidence.search.EvidenceChunkRepository.ChunkRow;
import io.saife.publicapi.domain.MsdsCache;
import io.saife.publicapi.repository.MsdsCacheRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 동봉 시드({@code seed/law_article.jsonl.gz}·{@code msds_cache.jsonl.gz}·{@code evidence_chunk.jsonl.gz})
 * → 각 테이블. <b>제1원칙: 심사위원은 키가 없다.</b> {@link io.saife.publicapi.service.PublicCacheSeedLoader}가
 * public_case·kosha_guide를 넣는 것과 같은 계약이고, 그 러너의 {@code run()} 끝에서 이어 호출된다.
 *
 * <p><b>테이블이 비어 있을 때만</b> 동작한다(테이블별 개별 판단 — evidence_chunk가 비어 있어도
 * law_article에 이미 데이터가 있으면 law_article은 건드리지 않는다). 이미 데이터가 있으면
 * 절대 부분 병합하지 않는다.
 *
 * <p>{@code evidence_chunk}는 두 패스로 넣는다: ① {@code searchable=false}(parent)를 전부 먼저
 * upsert해 refKey → id를 얻고, ② {@code searchable=true}(child)를 그 맵으로 parentId를 채워 넣는다.
 * {@link SeedExporter}는 parent를 먼저 쓰도록 정렬해 내보내지만 <b>그 순서를 신뢰하지 않는다</b> —
 * 파일이 손으로 재배열되거나 스트리밍 도중 잘려도 안전하도록, 파일 전체를 한 번 읽어 parent를
 * 모두 확정한 뒤에 child를 연결한다.
 *
 * <p>시드 파일에는 {@code ref_id}가 없다(재적재 시 원본 테이블의 auto-increment id가 그대로
 * 재현된다는 보장이 없다). {@link EvidenceChunkRepository}의 refKey → id 조회 맵 3종을 한 번 읽어
 * kind별로 실제 id를 채운다. 못 찾으면 0으로 두고 {@code [SEED]} warn을 남긴다(refKey 포함) —
 * 화면 표시는 refKey·metadata로 하므로 치명적이지 않지만, 시드-DB 불일치를 조용히 넘기지 않는다.
 *
 * <p><b>fix round 1 (F2):</b> {@code loadChunksFrom}의 두 패스 전체를
 * {@link TransactionTemplate}({@code PROPAGATION_REQUIRES_NEW})로 감싼다. 파일 중간에서
 * {@code VectorCodec.decode}가 손상된 임베딩을 만나 {@link IllegalArgumentException}을 던지면
 * 그 시점까지 이미 insert한 parent·child 전부가 롤백된다 — evidence_chunk가 "일부만 채워진 채"
 * 남으면 {@code countByKind().isEmpty()} 게이트가 더 이상 true를 주지 않아 다음 부팅에서도
 * 재시도가 안 되는 문제를 막는다. {@code load()}의 기존 catch는 그대로 두어, 이 예외가 올라와도
 * 로그만 남기고 앱은 정상 부팅한다(테이블이 비어 있으면 다음 부팅이 다시 시도한다).
 *
 * <p><b>REQUIRES_NEW를 고른 이유(NESTED 대신, 실측):</b> JPA savepoint 중첩
 * ({@code PROPAGATION_NESTED})을 먼저 시도했으나 이 프로젝트의 {@code JpaTransactionManager}가
 * {@code NestedTransactionNotSupportedException: JpaDialect does not support savepoints}로
 * 즉시 실패했다(별도 {@code HibernateJpaDialect} 빈 없이는 지원되지 않음, 이번 수정 범위 밖).
 * REQUIRES_NEW는 별도 물리 커넥션을 쓰므로, 호출부가 같은 테스트 트랜잭션 안에서 미리 저장해
 * 두고 아직 커밋하지 않은 참조 테이블 행은 이 메서드의 조회(caseIdsBySourceKey 등)가 보지
 * 못한다 — 그런 테스트는 해당 행을 먼저 커밋해 두거나(트랜잭션을 안 쓰거나) 그 사실을 감안해야
 * 한다(테스트 클래스 주석 참고).
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
    private final PlatformTransactionManager txManager;

    /** 부팅 훅(PublicCacheSeedLoader.run() 말미)에서 호출. 어느 단계가 실패해도 앱은 죽지 않는다 */
    public void load() {
        try {
            if (laws.count() == 0) {
                log.info("[SEED] 조문 {}건 적재", loadLaws());
            } else {
                log.debug("[SEED] law_article에 이미 데이터가 있어 동봉 캐시를 넣지 않는다");
            }
        } catch (Exception e) {
            log.error("[SEED] 조문 적재 실패", e);
        }
        try {
            if (msds.count() == 0) {
                log.info("[SEED] MSDS {}건 적재", loadMsds());
            } else {
                log.debug("[SEED] msds_cache에 이미 데이터가 있어 동봉 캐시를 넣지 않는다");
            }
        } catch (Exception e) {
            log.error("[SEED] MSDS 적재 실패", e);
        }
        try {
            if (chunks.countByKind().isEmpty()) {
                try (BufferedReader r = open("seed/evidence_chunk.jsonl.gz")) {
                    if (r != null) {
                        log.info("[SEED] 근거 청크 {}건 적재", loadChunksFrom(r));
                    }
                }
            } else {
                log.debug("[SEED] evidence_chunk에 이미 데이터가 있어 동봉 캐시를 넣지 않는다");
            }
        } catch (Exception e) {
            log.error("[SEED] 근거 청크 적재 실패 — 검색이 비어 있는 상태로 뜬다", e);
        }
    }

    int loadLaws() throws IOException {
        int n = 0;
        try (BufferedReader r = open("seed/law_article.jsonl.gz")) {
            if (r == null) {
                return 0;
            }
            List<LawArticle> buf = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode j = MAPPER.readTree(sanitize(line));
                buf.add(LawArticle.builder().lawId(j.path("lawId").asText()).lawName(j.path("lawName").asText())
                        .articleNo(j.path("articleNo").asInt()).articleSub(j.path("articleSub").asInt(0))
                        .paragraphNo(j.path("paragraphNo").asInt(0)).title(text(j, "title")).text(j.path("text").asText())
                        .effectiveOn(date(text(j, "effectiveOn"))).sourceUrl(lawSourceUrl(j))
                        .fetchedAt(OffsetDateTime.now()).build());
                if (buf.size() >= BATCH) {
                    laws.saveAll(buf);
                    n += buf.size();
                    buf.clear();
                }
            }
            if (!buf.isEmpty()) {
                laws.saveAll(buf);
                n += buf.size();
            }
        }
        return n;
    }

    int loadMsds() throws IOException {
        int n = 0;
        try (BufferedReader r = open("seed/msds_cache.jsonl.gz")) {
            if (r == null) {
                return 0;
            }
            List<MsdsCache> buf = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode j = MAPPER.readTree(sanitize(line));
                buf.add(MsdsCache.builder().chemId(j.path("chemId").asText()).chemNameKor(text(j, "chemNameKor"))
                        .casNo(text(j, "casNo")).unNo(text(j, "unNo")).sectionCode(j.path("sectionCode").asText())
                        .itemCode(text(j, "itemCode")).itemName(text(j, "itemName")).itemDetail(text(j, "itemDetail"))
                        .pictograms(text(j, "pictograms")).fetchedAt(OffsetDateTime.now()).build());
                if (buf.size() >= BATCH) {
                    msds.saveAll(buf);
                    n += buf.size();
                    buf.clear();
                }
            }
            if (!buf.isEmpty()) {
                msds.saveAll(buf);
                n += buf.size();
            }
        }
        return n;
    }

    /**
     * 청크 두 패스 적재의 진입점(테스트용 공개 메서드). 전체를 {@code PROPAGATION_REQUIRES_NEW}
     * 트랜잭션 하나로 감싼다 — 파일 중간에서 예외가 나도(예: 손상된 임베딩) 그때까지 insert한
     * 행 전부가 롤백되고, 부분 적재 상태로 남지 않는다. 독립된 물리 커넥션이라 호출부가 이미
     * 다른 트랜잭션 안에 있어도(테스트의 {@code @Transactional} 등) 영향받지 않고 즉시
     * 커밋/롤백된다 — 단, 그만큼 호출부의 미커밋 변경은 이 메서드에서 보이지 않는다(클래스
     * 주석의 "REQUIRES_NEW를 고른 이유" 참고).
     *
     * @return 적재된(부모+자식) 행 수
     */
    public int loadChunksFrom(BufferedReader r) throws IOException {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setName("EvidenceSeedLoader.loadChunksFrom");
        try {
            Integer n = tx.execute(status -> {
                try {
                    return loadChunksInTx(r);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return n == null ? 0 : n;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private int loadChunksInTx(BufferedReader r) throws IOException {
        Map<String, Long> caseIds = chunks.caseIdsBySourceKey();
        Map<String, Long> guideIds = chunks.guideIdsByNo();
        Map<String, Long> lawIds = chunks.lawIdsByKey();

        List<JsonNode> children = new ArrayList<>();
        Map<String, Long> parentIds = new HashMap<>();
        List<ChunkRow> buf = new ArrayList<>();
        List<String> bufKeys = new ArrayList<>();
        int n = 0;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode j = MAPPER.readTree(sanitize(line));
            if (!j.path("searchable").asBoolean(true)) {
                buf.add(row(j, null, caseIds, guideIds, lawIds));
                bufKeys.add(j.path("refKey").asText());
                if (buf.size() >= BATCH) {
                    n += flushParents(buf, bufKeys, parentIds);
                }
            } else {
                children.add(j);
            }
        }
        n += flushParents(buf, bufKeys, parentIds);

        for (JsonNode j : children) {
            String pk = text(j, "parentRefKey");
            Long pid = pk == null ? null : parentIds.get(pk);
            if (pk != null && pid == null) {
                // 같은 파일 안에서 못 찾았다 — 이미 DB에 있는(재적재) parent일 수 있으니 한 번 더 조회
                pid = chunks.findIdByRefKey(EvidenceKind.valueOf(j.path("kind").asText()), pk).orElse(null);
                if (pid == null) {
                    log.warn("[SEED] parent 미해결 — parentId 없이 적재 refKey={} parentRefKey={}",
                            j.path("refKey").asText(), pk);
                }
            }
            buf.add(row(j, pid, caseIds, guideIds, lawIds));
            if (buf.size() >= BATCH) {
                chunks.insertBatch(buf);
                n += buf.size();
                buf.clear();
            }
        }
        if (!buf.isEmpty()) {
            chunks.insertBatch(buf);
            n += buf.size();
        }
        return n;
    }

    /** parent 버퍼를 insert하고 refKey → id를 parentIds에 채운다. 호출부(스트림 중간/끝)에서 공유 */
    private int flushParents(List<ChunkRow> buf, List<String> keys, Map<String, Long> parentIds) {
        if (buf.isEmpty()) {
            return 0;
        }
        List<Long> ids = chunks.insertBatch(buf);
        for (int i = 0; i < ids.size(); i++) {
            parentIds.put(keys.get(i), ids.get(i));
        }
        int n = buf.size();
        buf.clear();
        keys.clear();
        return n;
    }

    private ChunkRow row(JsonNode j, Long parentId, Map<String, Long> caseIds, Map<String, Long> guideIds, Map<String, Long> lawIds) {
        Map<String, Object> meta = MAPPER.convertValue(j.path("metadata"), new TypeReference<Map<String, Object>>() {});
        EvidenceKind kind = EvidenceKind.valueOf(j.path("kind").asText());
        String refKey = j.path("refKey").asText();
        String chunkLevel = j.path("chunkLevel").asText("child");
        float[] embedding;
        try {
            embedding = VectorCodec.decode(text(j, "embedding"));
        } catch (IllegalArgumentException e) {
            // fix round 1 (F2): 손상된 시드는 조용히 건너뛰지 않는다 — 어느 refKey가 망가졌는지
            // 남기고 그대로 던져서 loadChunksFrom을 감싼 트랜잭션 전체를 롤백시킨다
            log.error("[SEED] 근거 청크 임베딩 손상 refKey={}: {}", refKey, e.getMessage());
            throw e;
        }
        return new ChunkRow(kind, resolveRefId(kind, refKey, chunkLevel, caseIds, guideIds, lawIds), refKey, chunkLevel,
                parentId, j.path("searchable").asBoolean(true), text(j, "sectionTitle"), j.path("title").asText(),
                j.path("text").asText(), meta == null ? Map.of() : meta, embedding);
    }

    /**
     * kind별로 refKey를 실제 참조 테이블의 id로 되짚는다. refKey 포맷(청크 빌더 계약):
     * CASE는 {@code source:sourceKey}, GUIDE는 {@code guideNo#...}, LAW child는
     * {@code lawId:articleNo:articleSub:paragraphNo}, LAW parent는 그 뒤에 {@code #p}가 붙는다.
     * LAW parent는 항 번호를 갖지 않으므로(조 전체 대표) 항상 정확한 id를 알 수는 없다 —
     * 이건 예상된 미해결이라 warn하지 않고 조용히 0으로 둔다. 그 외(CASE·GUIDE·LAW child)의
     * 미해결은 시드와 참조 테이블이 어긋났다는 신호라 {@link #lookupOrWarn}이 refKey를 남긴다.
     */
    private long resolveRefId(EvidenceKind kind, String refKey, String chunkLevel,
            Map<String, Long> caseIds, Map<String, Long> guideIds, Map<String, Long> lawIds) {
        return switch (kind) {
            case CASE_FATALITY, CASE_DISASTER -> lookupOrWarn(caseIds, refKey, refKey);
            case GUIDE -> lookupOrWarn(guideIds, refKey.split("#")[0], refKey);
            case LAW -> {
                if ("parent".equals(chunkLevel)) {
                    String base = refKey.endsWith("#p") ? refKey.substring(0, refKey.length() - 2) : refKey;
                    yield lawIds.getOrDefault(base + ":0", 0L);
                }
                yield lookupOrWarn(lawIds, refKey, refKey);
            }
            case MSDS -> 0L;
        };
    }

    /** fix round 1 (F1): 자연키 조회 실패를 0으로 조용히 넘기지 않는다 — refKey를 남겨 진단 가능하게 한다 */
    private long lookupOrWarn(Map<String, Long> ids, String naturalKey, String refKey) {
        Long id = ids.get(naturalKey);
        if (id != null) {
            return id;
        }
        log.warn("[SEED] refId 자연키 미해결 — ref_id=0으로 적재 refKey={} naturalKey={}", refKey, naturalKey);
        return 0L;
    }

    /**
     * 최종 리뷰 F1 — 시드 한 줄에서 법제처 OC 자격증명을 지운다(이중 방어). 동봉 시드는 이미
     * 스크럽했지만, 누군가 옛 DB에서 다시 내보낸 파일을 넣어도 OC가 테이블·카드로 새지 않게
     * JSON 파싱 전에 줄 단위로 거른다. URL 파라미터 모양({@code ?OC=}/{@code &OC=})만 건드린다.
     */
    static String sanitize(String line) {
        return LawUrls.stripOc(line);
    }

    /**
     * 조문 원문 링크. 옛 시드의 DRF API 링크(OC를 지워도 키 없이는 열리지 않는다)는 버리고
     * 사람용 조문 페이지로 다시 만든다. 이미 사람용 링크면 그대로 둔다.
     */
    static String lawSourceUrl(JsonNode j) {
        String url = text(j, "sourceUrl");
        if (url == null || url.contains("/DRF/")) {
            return LawUrls.articlePage(j.path("lawName").asText(), j.path("articleNo").asInt(), j.path("articleSub").asInt(0));
        }
        return LawUrls.stripOc(url);
    }

    private BufferedReader open(String resource) throws IOException {
        ClassPathResource cp = new ClassPathResource(resource);
        if (!cp.exists()) {
            log.warn("[SEED] {} 없음, 건너뜀", resource);
            return null;
        }
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(cp.getInputStream()), StandardCharsets.UTF_8));
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static LocalDate date(String s) {
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
