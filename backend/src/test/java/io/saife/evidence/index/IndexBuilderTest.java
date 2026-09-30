package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.repository.LawArticleRepository;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.evidence.search.SearchPolicy;
import io.saife.publicapi.domain.CrawlCheckpoint;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.CrawlCheckpointRepository;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IndexBuilderTest {
    private final EvidenceChunkRepository chunks = mock(EvidenceChunkRepository.class);
    private final PublicCaseRepository cases = mock(PublicCaseRepository.class);
    private final KoshaGuideRepository guides = mock(KoshaGuideRepository.class);
    private final LawArticleRepository laws = mock(LawArticleRepository.class);
    private final CrawlCheckpointRepository checkpoints = mock(CrawlCheckpointRepository.class);
    private final GuideTextSource guideText = mock(GuideTextSource.class);
    private final io.saife.evidence.chunk.ContextualEnricher enricher = mock(io.saife.evidence.chunk.ContextualEnricher.class);

    /** 768차원 더미 벡터. F2(차원 검증) 도입 후에는 길이만 맞으면 값은 중요하지 않다 */
    private static float[] vec() {
        return new float[SearchPolicy.EMBEDDING_DIMENSIONS];
    }

    private List<PublicCase> cases(int n) {
        return LongStream.rangeClosed(1, n).mapToObj(i -> PublicCase.builder().id(i).source("FATALITY").sourceKey("K" + i)
                .keyword("k" + i).contents("c" + i).accidentType(AccidentType.FALL).build()).toList();
    }

    @Test
    void 사례를_100건씩_임베딩해_넣는다() {
        when(cases.findAll()).thenReturn(cases(250));
        when(checkpoints.findById("INDEX_CASE_FATALITY")).thenReturn(Optional.empty());
        BatchEmbedder embedder = texts -> texts.stream().map(t -> vec()).toList();
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> { List<?> l = inv.getArgument(0); return l.stream().map(x -> 1L).toList(); });
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder, 5, true);
        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.CASE_FATALITY);
        assertThat(r.embedded()).isEqualTo(250);
        verify(chunks, times(3)).insertBatch(anyList());   // 100 + 100 + 50
        verify(checkpoints, atLeast(3)).save(any(CrawlCheckpoint.class));
    }

    // 최종 리뷰 F5: 데모 모드(임베딩 불가)면 아무것도 지우지 않고 SKIPPED
    @Test
    void 데모_모드면_아무것도_지우지_않고_SKIPPED() {
        BatchEmbedder demo = new BatchEmbedder() {
            @Override public List<float[]> embedAll(List<String> texts) { throw new DemoModeSkipped("demo"); }
            @Override public boolean available() { return false; }
        };
        when(checkpoints.findById("INDEX_CASE_FATALITY")).thenReturn(Optional.of(CrawlCheckpoint.builder()
                .dataset("INDEX_CASE_FATALITY").lastPage(3).savedCount(250).status(CrawlCheckpoint.STATUS_DONE).build()));
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, demo, 5, true);
        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.CASE_FATALITY, true);   // force여도 지우지 않는다
        assertThat(r.status()).isEqualTo("SKIPPED");
        verify(chunks, never()).deleteByKind(any());
        verify(chunks, never()).insertBatch(anyList());
        verify(checkpoints, never()).save(any(CrawlCheckpoint.class));
        verifyNoInteractions(cases);
    }

    // 최종 리뷰 F5: 체크포인트가 DONE이면 force 없이는 다시 만들지 않는다
    @Test
    void 완료된_kind는_force_없이는_다시_만들지_않는다() {
        when(cases.findAll()).thenReturn(cases(50));
        when(checkpoints.findById("INDEX_CASE_FATALITY")).thenReturn(Optional.of(CrawlCheckpoint.builder()
                .dataset("INDEX_CASE_FATALITY").lastPage(1).savedCount(50).status(CrawlCheckpoint.STATUS_DONE).build()));
        BatchEmbedder embedder = texts -> texts.stream().map(t -> vec()).toList();
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> { List<?> l = inv.getArgument(0); return l.stream().map(x -> 1L).toList(); });
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder, 5, true);

        IndexBuilder.IndexReport noForce = b.rebuild(EvidenceKind.CASE_FATALITY);
        assertThat(noForce.status()).isEqualTo(CrawlCheckpoint.STATUS_DONE);
        assertThat(noForce.chunks()).isEqualTo(50);
        assertThat(noForce.embedded()).isZero();
        verify(chunks, never()).deleteByKind(any());
        verify(chunks, never()).insertBatch(anyList());

        IndexBuilder.IndexReport forced = b.rebuild(EvidenceKind.CASE_FATALITY, true);
        assertThat(forced.status()).isEqualTo(CrawlCheckpoint.STATUS_DONE);
        assertThat(forced.embedded()).isEqualTo(50);
        verify(chunks).deleteByKind(EvidenceKind.CASE_FATALITY);
    }

    @Test
    void 실패_시_체크포인트_유지() {
        when(cases.findAll()).thenReturn(cases(250));
        when(checkpoints.findById("INDEX_CASE_FATALITY")).thenReturn(Optional.empty());
        int[] call = {0};
        BatchEmbedder embedder = texts -> { if (++call[0] == 2) throw new RuntimeException("429"); return texts.stream().map(t -> vec()).toList(); };
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> { List<?> l = inv.getArgument(0); return l.stream().map(x -> 1L).toList(); });
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder, 5, true);
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
        BatchEmbedder embedder = texts -> { embedCounts.add(texts.size()); return texts.stream().map(t -> vec()).toList(); };
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> { List<?> l = inv.getArgument(0); return LongStream.rangeClosed(1, l.size()).boxed().toList(); });
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder, 5, true);
        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.LAW);
        assertThat(r.chunks()).isEqualTo(3);      // parent 1 + child 2
        assertThat(r.embedded()).isEqualTo(2);    // child만
        assertThat(embedCounts).containsExactly(2);
    }

    // ---- Fix round 1 ----

    /**
     * 지침 51개, 각 1페이지 짧은 본문(제목줄 없음) → GuideChunkBuilder가 지침당 표지(#0)+본문 child(#c1)
     * 2개씩 만든다(패턴은 GuideChunkBuilderTest·splitSections 참고: 헤딩이 없으면 문단 그룹 1개로 뭉쳐
     * 자식 1개). 51*2=102건 중 체크포인트 lastPage=1(=1배치=100건 기완료)로 재개하면 남는 건
     * 마지막 지침(51번째) 몫 2건뿐이다 — enrich는 그 2건에만 불려야 한다.
     */
    @Test
    void 재개_시_이전_배치는_enrich하지_않는다() {
        List<KoshaGuide> list = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            list.add(KoshaGuide.builder().id((long) i).guideNo("G" + i).guideName("지침" + i).build());
        }
        when(guides.findAll()).thenReturn(list);
        when(guideText.pagesOf(any())).thenReturn(List.of("이것은 테스트 지침 본문 문장이다"));
        when(enricher.enrich(anyString(), anyString(), anyInt(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(checkpoints.findById("INDEX_GUIDE")).thenReturn(Optional.of(CrawlCheckpoint.builder()
                .dataset("INDEX_GUIDE").lastPage(1).savedCount(100).status(CrawlCheckpoint.STATUS_RUNNING).build()));
        List<Integer> embedCounts = new ArrayList<>();
        BatchEmbedder embedder = texts -> { embedCounts.add(texts.size()); return texts.stream().map(t -> vec()).toList(); };
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> { List<?> l = inv.getArgument(0); return LongStream.rangeClosed(1, l.size()).boxed().toList(); });
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder, 5, true);

        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.GUIDE);

        assertThat(r.embedded()).isEqualTo(2);              // 51번째 지침의 표지+본문 child만
        assertThat(embedCounts).containsExactly(2);         // embedAll 정확히 한 번, 2건짜리 배치
        verify(enricher, times(2)).enrich(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void 임베딩_차원이_다르면_FAILED로_기록하고_체크포인트를_보존한다() {
        when(cases.findAll()).thenReturn(cases(3));
        when(checkpoints.findById("INDEX_CASE_FATALITY")).thenReturn(Optional.empty());
        BatchEmbedder embedder = texts -> texts.stream().map(t -> new float[]{1f, 2f}).toList(); // 768이 아니다
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder, 5, true);

        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.CASE_FATALITY);

        assertThat(r.status()).isEqualTo(CrawlCheckpoint.STATUS_FAILED);
        assertThat(r.message()).contains("차원 불일치");
        ArgumentCaptor<CrawlCheckpoint> captor = ArgumentCaptor.forClass(CrawlCheckpoint.class);
        verify(checkpoints, atLeastOnce()).save(captor.capture());
        CrawlCheckpoint saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(CrawlCheckpoint.STATUS_FAILED);
        assertThat(saved.getLastError()).contains("차원 불일치");
        assertThat(saved.getLastPage()).isEqualTo(0); // 배치를 하나도 못 끝냈다 — lastPage 그대로
        verify(chunks, never()).insertBatch(anyList());
    }

    @Test
    void draftsOf_예외도_FAILED로_남기고_ALL이_이어갈_수_있게_한다() {
        when(cases.findAll()).thenThrow(new RuntimeException("DB 다운"));
        when(checkpoints.findById("INDEX_CASE_FATALITY")).thenReturn(Optional.empty());
        BatchEmbedder embedder = texts -> texts.stream().map(t -> vec()).toList(); // 호출되지 않아야 한다
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder, 5, true);

        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.CASE_FATALITY);

        assertThat(r.status()).isEqualTo(CrawlCheckpoint.STATUS_FAILED);
        assertThat(r.message()).contains("DB 다운");
        verify(chunks, never()).insertBatch(anyList());
        verify(checkpoints, atLeastOnce()).save(any(CrawlCheckpoint.class));
    }

    // ---- Task 5b: enrichment 병렬화 + 절단선 스위치 ----

    /** chunkText 안의 "G{n}" 표기에서 지침 번호를 뽑는다. 정규식 import 없이 순수 문자열 스캔으로 처리 */
    private static int guideNumberIn(String chunkText) {
        int idx = chunkText.indexOf('G');
        if (idx < 0) return 0;
        StringBuilder digits = new StringBuilder();
        for (int i = idx + 1; i < chunkText.length() && Character.isDigit(chunkText.charAt(i)); i++) {
            digits.append(chunkText.charAt(i));
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }

    /**
     * 지침 5개(각 표지+본문 2건씩=10건)를 parallelism=3 풀에서 보강한다. 지침 번호가 작을수록
     * enrich가 더 오래 걸리게 만들어(완료 순서 ≠ 입력 순서) 인덱스 기반 join이 완료 순서가 아니라
     * **입력 순서**로 결과를 모으는지 검증한다 — 그렇지 않으면 뒤섞인 순서로 insertBatch에 들어간다.
     */
    @Test
    void guide_enrich는_병렬로_실행되고_입력_순서를_보존한다() {
        List<KoshaGuide> list = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            list.add(KoshaGuide.builder().id((long) i).guideNo("G" + i).guideName("지침" + i).build());
        }
        when(guides.findAll()).thenReturn(list);
        when(guideText.pagesOf(any())).thenAnswer(inv -> {
            KoshaGuide g = inv.getArgument(0);
            return List.of("이것은 " + g.getGuideNo() + "번 지침의 테스트 본문 문장이다");
        });
        when(checkpoints.findById("INDEX_GUIDE")).thenReturn(Optional.empty());
        when(enricher.enrich(anyString(), anyString(), anyInt(), anyString())).thenAnswer(inv -> {
            String chunkText = inv.getArgument(1);
            int guideNo = guideNumberIn(chunkText);
            try {
                Thread.sleep(Math.max(0, (6 - guideNo) * 15L)); // 지침1이 제일 늦게, 지침5가 제일 먼저 끝난다
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "[E]" + chunkText;
        });
        List<EvidenceChunkRepository.ChunkRow> captured = new ArrayList<>();
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> {
            List<EvidenceChunkRepository.ChunkRow> l = inv.getArgument(0);
            captured.addAll(l);
            return l.stream().map(x -> 1L).toList();
        });
        BatchEmbedder embedder = texts -> texts.stream().map(t -> vec()).toList();
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder, 3, true);

        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.GUIDE);

        assertThat(r.embedded()).isEqualTo(10);
        verify(enricher, times(10)).enrich(anyString(), anyString(), anyInt(), anyString());
        assertThat(captured).hasSize(10);
        for (EvidenceChunkRepository.ChunkRow row : captured) {
            assertThat(row.text()).startsWith("[E]");
        }
        List<String> expectedOrder = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            expectedOrder.add("G" + i + "#0");
            expectedOrder.add("G" + i + "#c1");
        }
        assertThat(captured.stream().map(EvidenceChunkRepository.ChunkRow::refKey).toList())
                .containsExactlyElementsOf(expectedOrder);
    }

    /** 절단선 ⑤: enrich-enabled=false면 enricher를 아예 안 부르고 원문 그대로 쓰며, message에 표식을 남긴다 */
    @Test
    void enrich_enabled가_false면_enricher를_0회_호출하고_원문을_쓴다() {
        List<KoshaGuide> list = List.of(KoshaGuide.builder().id(1L).guideNo("G1").guideName("지침1").build());
        when(guides.findAll()).thenReturn(list);
        when(guideText.pagesOf(any())).thenReturn(List.of("이것은 테스트 지침 본문 문장이다"));
        when(checkpoints.findById("INDEX_GUIDE")).thenReturn(Optional.empty());
        when(chunks.insertBatch(anyList())).thenAnswer(inv -> { List<?> l = inv.getArgument(0); return l.stream().map(x -> 1L).toList(); });
        BatchEmbedder embedder = texts -> texts.stream().map(t -> vec()).toList();
        IndexBuilder b = new IndexBuilder(chunks, cases, guides, laws, checkpoints, guideText, enricher, embedder, 5, false);

        IndexBuilder.IndexReport r = b.rebuild(EvidenceKind.GUIDE);

        assertThat(r.embedded()).isEqualTo(2); // 표지 + 본문
        verify(enricher, never()).enrich(anyString(), anyString(), anyInt(), anyString());
        assertThat(r.message()).contains("enrichment=off");
    }
}
