package io.saife.evidence.index;

import io.saife.evidence.EvidenceKind;
import io.saife.evidence.chunk.CaseChunkBuilder;
import io.saife.evidence.chunk.ChunkDraft;
import io.saife.evidence.chunk.ContextualEnricher;
import io.saife.evidence.chunk.GuideChunkBuilder;
import io.saife.evidence.chunk.LawChunkBuilder;
import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.repository.LawArticleRepository;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.evidence.search.EvidenceChunkRepository.ChunkRow;
import io.saife.evidence.search.SearchPolicy;
import io.saife.publicapi.domain.CrawlCheckpoint;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.CrawlCheckpointRepository;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * kind별로 청크를 만들어 임베딩하고 evidence_chunk에 넣는다. 100건 배치, 배치마다 체크포인트.
 *
 * <p>parent(searchable=false)는 텍스트만 넣고 임베딩하지 않는다 — 전역 제약. parent를 먼저
 * upsert해 refKey→id를 얻고, child는 그 id를 parentId로 채워 넣는다.
 *
 * <p>재실행(rebuild)은 멱등이다: 체크포인트가 없으면(또는 DONE인데 {@code force=true}면) 기존 kind
 * 청크를 지우고 처음부터 다시 만든다. DONE이면 {@code force} 없이는 아무것도 하지 않는다.
 * RUNNING/FAILED면 지우지 않고 lastPage(배치 번호) 다음부터 이어받는다. 데모 모드면 아무것도
 * 지우지 않고 SKIPPED(최종 리뷰 F5).
 *
 * <p>문맥 보강(GUIDE의 {@link ContextualEnricher})은 실제로 임베딩할 배치에만 지연 적용한다 —
 * 재개 이전 배치(이미 임베딩 끝난 분)는 draft만 다시 만들 뿐, LLM을 다시 부르지 않는다.
 *
 * <p>draftsOf()·parent insert·배치 루프 전체가 하나의 try 블록 안에 있다 — 어디서 예외가 나도
 * 체크포인트를 FAILED로 남기고 {@link IndexReport}를 돌려줄 뿐, 밖으로 던지지 않는다. 그래야
 * kind=ALL 재구축에서 한 kind가 실패해도 나머지 kind가 이어서 실행된다.
 */
@Slf4j
@Service
public class IndexBuilder {
    static final int BATCH = 100;

    public record IndexReport(EvidenceKind kind, int chunks, int embedded, int skipped, String status, String message) {}

    /** GUIDE 청크의 문맥 보강용 원문. 본문이 없는(pages 빈) 지침은 여기 들어오지 않는다 — 보강 생략 */
    private record GuideDoc(String fullText, String guideName) {}

    /** draftsOf()의 결과. drafts는 즉시(LLM 없이) 만들지만, GUIDE 보강은 배치 처리 시점까지 미룬다 */
    private record Drafted(List<ChunkDraft> drafts, Map<Long, GuideDoc> guideFullText) {}

    private final EvidenceChunkRepository chunks;
    private final PublicCaseRepository cases;
    private final KoshaGuideRepository guides;
    private final LawArticleRepository laws;
    private final CrawlCheckpointRepository checkpoints;
    private final GuideTextSource guideText;
    private final ContextualEnricher enricher;
    private final BatchEmbedder embedder;

    /** 절단선 ⑤: false면 enrich 자체를 호출하지 않고 원문 그대로 임베딩한다 */
    private final boolean enrichEnabled;
    /** 지침 enrichment 병렬도. 1 이하면 기존 순차 경로(풀 없음) */
    private final int enrichParallelism;
    /** enrichParallelism&gt;1일 때만 생성되는 고정 크기 풀. IndexBuilder가 소유하고 {@link #shutdown()}에서 정리한다 */
    private final ExecutorService enrichPool;

    @Autowired
    public IndexBuilder(EvidenceChunkRepository chunks, PublicCaseRepository cases, KoshaGuideRepository guides,
                         LawArticleRepository laws, CrawlCheckpointRepository checkpoints, GuideTextSource guideText,
                         ContextualEnricher enricher, BatchEmbedder embedder,
                         @Value("${saife.index.enrich-parallelism:5}") int enrichParallelism,
                         @Value("${saife.index.enrich-enabled:true}") boolean enrichEnabled) {
        this.chunks = chunks;
        this.cases = cases;
        this.guides = guides;
        this.laws = laws;
        this.checkpoints = checkpoints;
        this.guideText = guideText;
        this.enricher = enricher;
        this.embedder = embedder;
        this.enrichParallelism = enrichParallelism;
        this.enrichEnabled = enrichEnabled;
        // 생성자 파라미터 자체가 @Value로 이미 해석된 값이라(필드 주입과 달리 순서 문제가 없다),
        // 풀은 생성자에서 바로 만든다. parallelism<=1이면 풀을 만들지 않고 기존 순차 경로를 쓴다
        this.enrichPool = enrichParallelism > 1 ? Executors.newFixedThreadPool(enrichParallelism, indexEnrichThreadFactory()) : null;
    }

    private static ThreadFactory indexEnrichThreadFactory() {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, "index-enrich-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdown() {
        if (enrichPool != null) enrichPool.shutdown();
    }

    /** {@code force=false}와 같다 — 완료된 kind는 다시 만들지 않는다 */
    public IndexReport rebuild(EvidenceKind kind) {
        return rebuild(kind, false);
    }

    /**
     * kind 하나를 (재)구축한다. 최종 리뷰 F5의 두 가드가 <b>데이터를 건드리기 전에</b> 먼저 선다:
     * <ol>
     *   <li>임베딩을 못 부르는 상태(데모 모드·키 없음)면 {@code SKIPPED}를 돌려주고 아무것도 지우지 않는다.
     *       예전에는 {@code deleteByKind}를 먼저 하고 첫 배치에서야 {@link BatchEmbedder.DemoModeSkipped}를
     *       받아, 키 없는 심사위원 클론에서 관리자 API 한 번에 CASE·GUIDE child가 전부 사라졌다
     *       (시드 로더는 테이블이 <i>완전히</i> 비어야 다시 넣으므로 {@code down -v} 전까지 복구 불가).</li>
     *   <li>체크포인트가 DONE이면 기존 결과를 그대로 알리고 끝낸다. 다시 만들려면 {@code force=true}.
     *       A4 Task 6의 "DONE에 rebuild를 불렀더니 전체가 처음부터 다시 돌았다"와 같은 뿌리다.</li>
     * </ol>
     */
    public IndexReport rebuild(EvidenceKind kind, boolean force) {
        String ds = "INDEX_" + kind.name();
        if (!embedder.available()) {
            log.info("[INDEX] {} 생략(데모 모드) — 기존 청크를 지우지 않는다", kind);
            return new IndexReport(kind, 0, 0, 0, "SKIPPED", "데모 모드 — 임베딩을 부를 수 없어 기존 색인을 그대로 둔다");
        }
        CrawlCheckpoint cp = checkpoints.findById(ds).orElse(null);
        boolean done = cp != null && CrawlCheckpoint.STATUS_DONE.equals(cp.getStatus());
        if (done && !force) {
            log.info("[INDEX] {} 이미 완료(체크포인트 DONE) — force=true 없이는 다시 만들지 않는다", kind);
            return new IndexReport(kind, cp.getSavedCount(), 0, 0, CrawlCheckpoint.STATUS_DONE,
                    "이미 완료 — 다시 만들려면 force=true");
        }
        boolean fresh = cp == null || done;
        int startBatch = fresh ? 0 : cp.getLastPage();
        // embeddedThisRun: 이번 rebuild() 호출에서 새로 임베딩한 건수(재개 이전 분은 포함하지 않는다).
        // 체크포인트에는 누적치(startBatch*BATCH + embeddedThisRun)를 저장한다 — 재개 판단은 lastPage(batchNo)로 한다
        int embeddedThisRun = 0;
        int batchNo = startBatch;
        int total = 0;
        List<ChunkDraft> children = List.of();
        try {
            // 멱등 재구축: 체크포인트가 없거나 완료 상태일 때만 지운다. RUNNING/FAILED는 이어받는다(지우지 않음)
            if (fresh) chunks.deleteByKind(kind);

            Drafted drafted = draftsOf(kind);
            List<ChunkDraft> drafts = drafted.drafts();
            total = drafts.size();

            // parent 먼저 upsert해 refKey→id를 만든다 (임베딩 없음). 재실행 때 다시 넣어도 upsert라 안전하다
            Map<String, Long> parentIds = new HashMap<>();
            List<ChunkRow> parentRows = drafts.stream().filter(d -> !d.searchable()).map(d -> row(d, null, null)).toList();
            if (!parentRows.isEmpty()) {
                List<Long> ids = chunks.insertBatch(parentRows);
                for (int i = 0; i < ids.size(); i++) parentIds.put(parentRows.get(i).refKey(), ids.get(i));
            }

            children = drafts.stream().filter(ChunkDraft::searchable).toList();
            for (int from = startBatch * BATCH; from < children.size(); from += BATCH) {
                List<ChunkDraft> rawSlice = children.subList(from, Math.min(children.size(), from + BATCH));
                // 문맥 보강은 "이번에 실제로 임베딩하는 배치"에만 한다 — 재개 이전 배치는 다시 만들지 않는다
                List<ChunkDraft> slice = enrichSlice(rawSlice, drafted.guideFullText());
                List<float[]> vectors = embedder.embedAll(slice.stream().map(ChunkDraft::text).toList());
                checkDimensions(vectors);
                List<ChunkRow> rows = new ArrayList<>();
                for (int i = 0; i < slice.size(); i++) {
                    ChunkDraft d = slice.get(i);
                    Long parentId = null;
                    if (d.parentRefKey() != null) {
                        parentId = parentIds.get(d.parentRefKey());
                        if (parentId == null) {
                            log.warn("[INDEX] parent refKey 미해결 child={} parent={}", d.refKey(), d.parentRefKey());
                        }
                    }
                    rows.add(row(d, parentId, vectors.get(i)));
                }
                chunks.insertBatch(rows);
                embeddedThisRun += slice.size();
                batchNo++;
                save(ds, batchNo, startBatch * BATCH + embeddedThisRun, CrawlCheckpoint.STATUS_RUNNING, null);
                log.info("[INDEX] {} 배치 {} — {}/{}", kind, batchNo, startBatch * BATCH + embeddedThisRun, children.size());
            }
            save(ds, batchNo, startBatch * BATCH + embeddedThisRun, CrawlCheckpoint.STATUS_DONE, null);
            String message = enrichEnabled ? "완료" : "완료 (enrichment=off)";
            return new IndexReport(kind, total, embeddedThisRun, total - children.size(), CrawlCheckpoint.STATUS_DONE, message);
        } catch (BatchEmbedder.DemoModeSkipped e) {
            // 데모 모드는 실패가 아니다 — 체크포인트를 건드리지 않는다. 다음 라이브 실행이 그대로 이어받는다
            log.info("[INDEX] {} 생략(데모 모드): {}", kind, e.getMessage());
            return new IndexReport(kind, total, embeddedThisRun, total - children.size(), "SKIPPED", e.getMessage());
        } catch (Exception e) {
            // draftsOf()·parent insert·배치 루프 어디서 터져도 여기서 잡는다 — 밖으로 던지면
            // ALL 재구축에서 뒤 kind가 아예 실행되지 않는다
            save(ds, batchNo, startBatch * BATCH + embeddedThisRun, CrawlCheckpoint.STATUS_FAILED, e.toString());
            log.error("[INDEX] {} 중단 배치 {} — 다시 실행하면 이어받는다", kind, batchNo, e);
            return new IndexReport(kind, total, embeddedThisRun, total - children.size(), CrawlCheckpoint.STATUS_FAILED, "중단: " + e.getMessage());
        }
    }

    /** 임베딩 차원이 어긋나면 즉시 실패시킨다 — 잘못된 벡터가 조용히 깔리는 것보다 배치를 통째로 버리는 게 낫다 */
    private void checkDimensions(List<float[]> vectors) {
        for (float[] v : vectors) {
            if (v.length != SearchPolicy.EMBEDDING_DIMENSIONS) {
                throw new IllegalStateException(
                        "[INDEX] 임베딩 차원 불일치: expected " + SearchPolicy.EMBEDDING_DIMENSIONS + ", got " + v.length);
            }
        }
    }

    /**
     * 배치(최대 100건) 안의 child 전체에 대해 {@link #enrichIfGuide}를 호출한다.
     *
     * <p>절단선 ⑤({@code enrichEnabled=false})면 enricher를 아예 부르지 않고 원문 그대로 돌려준다.
     * 풀이 없으면(parallelism&lt;=1) 기존 순차 경로. 풀이 있으면 {@link CompletableFuture}를
     * 입력 순서 그대로 인덱스로 join해 **결과 순서를 입력 순서와 동일하게 유지**한다 — 순서가
     * 흐트러지면 embedAll()에 넘기는 텍스트와 이후 row 조립이 어긋난다.
     *
     * <p>enrich 자체는 {@link ContextualEnricher#enrich}가 이미 실패를 흡수해 원문을 돌려주므로
     * 예외가 여기까지 올라오지 않는다. 작업 안에서 난 예외가 {@code join()}에서
     * {@link CompletionException}으로 나오면 해당 청크만 원문으로 두고 warn만 남긴다.
     * <b>풀 거부는 여기서 잡히지 않는다</b> — {@code supplyAsync}가 {@code RejectedExecutionException}을
     * 제출 시점에 동기적으로 던지므로(이 try 밖), {@link #rebuild}의 catch로 올라가 kind가
     * FAILED로 기록되고 다음 실행이 체크포인트부터 이어받는다(최종 리뷰 F20).
     */
    private List<ChunkDraft> enrichSlice(List<ChunkDraft> rawSlice, Map<Long, GuideDoc> guideFullText) {
        if (!enrichEnabled) return rawSlice;
        if (enrichPool == null) {
            return rawSlice.stream().map(d -> enrichIfGuide(d, guideFullText)).toList();
        }
        List<CompletableFuture<ChunkDraft>> futures = rawSlice.stream()
                .map(d -> CompletableFuture.supplyAsync(() -> enrichIfGuide(d, guideFullText), enrichPool))
                .toList();
        List<ChunkDraft> result = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                result.add(futures.get(i).join());
            } catch (CompletionException e) {
                log.warn("[INDEX] enrich 실패, 원문 사용 refKey={}: {}", rawSlice.get(i).refKey(), e.getMessage());
                result.add(rawSlice.get(i));
            }
        }
        return result;
    }

    /** GUIDE 청크만 문맥 보강한다. 본문이 없던 지침(guideFullText에 없음)은 원문 그대로 돌려준다 */
    private ChunkDraft enrichIfGuide(ChunkDraft d, Map<Long, GuideDoc> guideFullText) {
        if (d.kind() != EvidenceKind.GUIDE) return d;
        GuideDoc doc = guideFullText.get(d.refId());
        if (doc == null) return d;
        int head = Math.min(40, d.text().length());
        int offset = head == 0 ? 0 : Math.max(0, doc.fullText().indexOf(d.text().substring(0, head)));
        return d.withText(enricher.enrich(doc.fullText(), d.text(), offset, doc.guideName()));
    }

    private Drafted draftsOf(EvidenceKind kind) {
        return switch (kind) {
            case CASE_FATALITY, CASE_DISASTER -> {
                String source = kind == EvidenceKind.CASE_FATALITY ? "FATALITY" : "DISASTER";
                List<ChunkDraft> out = cases.findAll().stream().filter(c -> source.equals(c.getSource())).map(CaseChunkBuilder::build).toList();
                yield new Drafted(out, Map.of());
            }
            case LAW -> {
                Map<String, List<LawArticle>> byArticle = new LinkedHashMap<>();
                for (LawArticle a : laws.findAll()) {
                    byArticle.computeIfAbsent(a.getLawId() + ":" + a.getArticleNo() + ":" + a.getArticleSub(), k -> new ArrayList<>()).add(a);
                }
                List<ChunkDraft> out = new ArrayList<>();
                for (List<LawArticle> g : byArticle.values()) {
                    g.sort(Comparator.comparingInt(LawArticle::getParagraphNo));
                    out.addAll(LawChunkBuilder.build(g));
                }
                yield new Drafted(out, Map.of());
            }
            case GUIDE -> {
                List<ChunkDraft> out = new ArrayList<>();
                Map<Long, GuideDoc> guideFullText = new HashMap<>();
                for (KoshaGuide g : guides.findAll()) {
                    List<String> pages = guideText.pagesOf(g);
                    // draft 생성 자체는 LLM 없이 싸다 — 재개 여부와 무관하게 항상 전부 만든다.
                    // 본문이 없으면 GuideChunkBuilder가 표지 청크(guideNo#0)만 만든다(전역 제약)
                    out.addAll(GuideChunkBuilder.build(g.getId(), g.getGuideNo(), g.getGuideName(), g.getAnnouncedOn(), pages));
                    if (!pages.isEmpty()) guideFullText.put(g.getId(), new GuideDoc(String.join("\n", pages), g.getGuideName()));
                }
                yield new Drafted(out, guideFullText);
            }
            case MSDS -> new Drafted(List.of(), Map.of());
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
