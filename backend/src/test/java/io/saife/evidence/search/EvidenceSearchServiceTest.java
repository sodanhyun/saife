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

    /** caseHit()는 항상 CASE_FATALITY다 — CASE_DISASTER 쪽 후보를 만들 때 쓴다 */
    private ChunkHit disasterHit(long id, String axis, String business, boolean image, double score) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("accidentType", axis); meta.put("business", business); meta.put("hasImage", image);
        return new ChunkHit(id, EvidenceKind.CASE_DISASTER, id, "DISASTER:" + id, "child", null, null, "[추락] 재해사례 " + id, "[맥락]\n본문 " + id, meta, score);
    }

    private ChunkHit guideHit(long id, String guideNo) {
        Map<String, Object> meta = Map.of("guideNo", guideNo);
        return new ChunkHit(id, EvidenceKind.GUIDE, id, guideNo + "#" + id, "child", null, "s", "[KOSHA GUIDE " + guideNo + "] x", "t" + id, meta, 0);
    }

    private EvidenceChunkRepository repo = mock(EvidenceChunkRepository.class);
    private QueryEmbedder embedder = mock(QueryEmbedder.class);
    private LlmReranker reranker = mock(LlmReranker.class);
    private ParentChunkExpander expander = new ParentChunkExpander(repo);

    private EvidenceSearchService service() { return new EvidenceSearchService(repo, embedder, expander, reranker); }

    /**
     * H2 수정 후: 사례 검색은 kind별로 독립 조회를 돌린다({@code EvidenceSearchService.retrieveAndBoost}).
     * 그래서 이 테스트들의 목 데이터(전부 {@code caseHit()} = CASE_FATALITY)는 CASE_FATALITY
     * 조회 쪽에만 매칭시킨다 — CASE_DISASTER 조회는 목을 안 걸어두면 Mockito 기본값(빈 리스트)을
     * 돌려주므로 그대로 둔다. {@code anySet()}으로 뭉뚱그리면 같은 목 데이터가 두 조회 모두에
     * 잡혀 결과가 중복된다(직접 실측 확인).
     */
    @Test
    void 키_없으면_키워드_폴백() {
        when(embedder.available()).thenReturn(false);
        when(repo.keywordSearch(anyString(), eq(Set.of(EvidenceKind.CASE_FATALITY)), any(), any(), anyInt()))
                .thenReturn(List.of(caseHit(1, "FALL", "제조업", true, 0.5)));
        List<Evidence> out = service().search(SearchRequest.cases("사다리 추락", AccidentType.FALL, "제조업", 3));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).origin()).isEqualTo(Origin.KEYWORD_FALLBACK);
        verify(embedder, never()).embed(anyString());
        verifyNoInteractions(reranker);
        verify(repo, never()).vectorSearch(any(), anySet(), any(), any(), anyInt());
    }

    @Test
    void 벡터_키워드_RRF_리랭크_보정_순으로_흐른다() {
        when(embedder.available()).thenReturn(true);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{1f}));
        when(repo.vectorSearch(any(), eq(Set.of(EvidenceKind.CASE_FATALITY)), any(), any(), anyInt()))
                .thenReturn(List.of(caseHit(1, "FALL", "제조업", false, 0.9), caseHit(2, "CAUGHT", "건설업", true, 0.8)));
        when(repo.keywordSearch(anyString(), eq(Set.of(EvidenceKind.CASE_FATALITY)), any(), any(), anyInt()))
                .thenReturn(List.of(caseHit(2, "CAUGHT", "건설업", true, 0.3)));
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
        when(repo.keywordSearch(anyString(), anySet(), any(), any(), anyInt())).thenReturn(List.of(a, b));
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

    // 리뷰 F1: 리랭크를 건너뛴 경로에서 RRF 원점수(≤ 1/61 ≈ 0.0164)를 정규화하지 않고 그대로
    // 보정치(최대 0.10)와 더하면, 관련성이 아니라 축·업종·사진 유무가 정렬을 결정해버린다.
    @Test
    void 리랭크_생략시_RRF_점수를_0_1로_정규화한_뒤_보정한다() {
        when(embedder.available()).thenReturn(false);
        ChunkHit a = caseHit(1, "CAUGHT", "건설업", false, 0);   // rank0, 축·업종 불일치 → 보정 없음
        ChunkHit b = caseHit(2, "FALL", "제조업", false, 0);      // rank1, 같은 축+제조업
        ChunkHit c = caseHit(3, "FALL", "제조업", true, 0);       // rank2, 같은 축+제조업+사진
        when(repo.keywordSearch(anyString(), eq(Set.of(EvidenceKind.CASE_FATALITY)), any(), any(), anyInt()))
                .thenReturn(List.of(a, b, c));

        List<Evidence> out = service().search(SearchRequest.cases("사다리", AccidentType.FALL, "제조업", 3).withoutRerank());

        double normA = (SearchPolicy.KEYWORD_WEIGHT / (SearchPolicy.RRF_K + 0 + 1)) / SearchPolicy.RRF_MAX;
        double normB = (SearchPolicy.KEYWORD_WEIGHT / (SearchPolicy.RRF_K + 1 + 1)) / SearchPolicy.RRF_MAX
                + SearchPolicy.BOOST_SAME_AXIS + SearchPolicy.BOOST_MANUFACTURING;
        double normC = (SearchPolicy.KEYWORD_WEIGHT / (SearchPolicy.RRF_K + 2 + 1)) / SearchPolicy.RRF_MAX
                + SearchPolicy.BOOST_SAME_AXIS + SearchPolicy.BOOST_MANUFACTURING + SearchPolicy.BOOST_HAS_IMAGE;

        assertThat(out).hasSize(3);
        assertThat(out.get(0).refId()).isEqualTo(3L);
        assertThat(out.get(0).score()).isEqualTo(Math.round(normC * 1000) / 1000.0);
        assertThat(out.get(1).refId()).isEqualTo(2L);
        assertThat(out.get(1).score()).isEqualTo(Math.round(normB * 1000) / 1000.0);
        assertThat(out.get(2).refId()).isEqualTo(1L);
        assertThat(out.get(2).score()).isEqualTo(Math.round(normA * 1000) / 1000.0);
    }

    // 같은 리뷰 항목: 순위 격차가 크면(rank0 vs rank30) 보정을 더해도 관련성 우위가 뒤집히지 않아야 한다.
    @Test
    void 순위_격차가_크면_보정을_더해도_관련성이_이긴다() {
        when(embedder.available()).thenReturn(false);
        int k = 31;   // wide = k*CANDIDATE_MULTIPLIER, 후보 폭이 rank30까지 전부 포함하도록 넉넉히 잡는다
        List<ChunkHit> hits = new ArrayList<>();
        hits.add(caseHit(1, "CAUGHT", "건설업", false, 0));                      // rank0 = A, 보정 없음
        for (int i = 1; i < 30; i++) hits.add(caseHit(100 + i, "CAUGHT", "건설업", false, 0));  // 채움 rank1~29
        hits.add(caseHit(2, "FALL", "제조업", false, 0));                        // rank30 = B, 같은 축+제조업
        when(repo.keywordSearch(anyString(), eq(Set.of(EvidenceKind.CASE_FATALITY)), any(), any(), anyInt()))
                .thenReturn(hits);

        List<Evidence> out = service().search(SearchRequest.cases("사다리", AccidentType.FALL, "제조업", k).withoutRerank());

        double normA = (SearchPolicy.KEYWORD_WEIGHT / (SearchPolicy.RRF_K + 0 + 1)) / SearchPolicy.RRF_MAX;
        double normB = (SearchPolicy.KEYWORD_WEIGHT / (SearchPolicy.RRF_K + 30 + 1)) / SearchPolicy.RRF_MAX
                + SearchPolicy.BOOST_SAME_AXIS + SearchPolicy.BOOST_MANUFACTURING;
        assertThat(normA).isGreaterThan(normB);   // 관련성 격차가 보정치 0.08을 이긴다

        assertThat(out.get(0).refId()).isEqualTo(1L);
        assertThat(out.get(0).score()).isEqualTo(Math.round(normA * 1000) / 1000.0);
        Evidence bCard = out.stream().filter(e -> e.refId() == 2L).findFirst().orElseThrow();
        assertThat(bCard.score()).isEqualTo(Math.round(normB * 1000) / 1000.0);
    }

    // 최종 리뷰 F2: 리랭크 10점(1.0) + 같은 축 + 제조업 + 사진 보정 → 정렬용 점수는 1.10이지만
    // 카드에 싣는 "유사도"는 1.0을 넘지 않는다
    @Test
    void 보정을_더해도_표시_점수는_1을_넘지_않는다() {
        when(embedder.available()).thenReturn(true);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{1f}));
        when(repo.vectorSearch(any(), eq(Set.of(EvidenceKind.CASE_FATALITY)), any(), any(), anyInt()))
                .thenReturn(List.of(caseHit(1, "FALL", "제조업", true, 0.9), caseHit(2, "FALL", "제조업", false, 0.8)));
        when(repo.findParents(anySet())).thenReturn(Map.of());
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<ChunkHit> in = inv.getArgument(1);
            return in.stream().map(h -> h.withScore(1.0)).toList();
        });
        List<Evidence> out = service().search(SearchRequest.cases("사다리", AccidentType.FALL, "제조업", 3));
        assertThat(out).isNotEmpty();
        assertThat(out).allSatisfy(e -> assertThat(e.score()).isBetween(0.0, 1.0));
        assertThat(out.get(0).refId()).isEqualTo(1L);   // 정렬은 보정값(사진 +0.02) 그대로
    }

    // 최종 리뷰 F2: 리랭커가 입력을 그대로 돌려주는 경로(후보 ≤ k 단락, 파싱·호출 실패 폴백)도
    // 0~1로 정규화된 점수를 받아야 한다 — 예전에는 RRF 원점수(≈0.016)가 "유사도 2%"로 찍혔다
    @Test
    void 리랭크_폴백_경로도_정규화된_점수를_싣는다() {
        when(embedder.available()).thenReturn(true);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{1f}));
        ChunkHit a = caseHit(1, "CAUGHT", "건설업", false, 0.9);
        when(repo.vectorSearch(any(), eq(Set.of(EvidenceKind.CASE_FATALITY)), any(), any(), anyInt())).thenReturn(List.of(a));
        when(repo.keywordSearch(anyString(), eq(Set.of(EvidenceKind.CASE_FATALITY)), any(), any(), anyInt())).thenReturn(List.of(a));
        when(repo.findParents(anySet())).thenReturn(Map.of());
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(1));   // 원본 그대로
        List<Evidence> out = service().search(SearchRequest.cases("사다리", AccidentType.FALL, null, 3));
        assertThat(out).hasSize(1);
        // 양쪽 레그 rank0 → RRF 최대치 → 정규화 1.0 (보정 없음)
        assertThat(out.get(0).score()).isEqualTo(1.0);
    }

    // 최종 리뷰 F7: 요청의 업종이 SQL 두 다리에 그대로 전달된다(빈 문자열은 필터 없음)
    @Test
    void 업종은_두_다리_검색에_전달된다() {
        when(embedder.available()).thenReturn(true);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{1f}));
        service().search(SearchRequest.guides("사다리", 3));   // 지침은 업종 없음
        verify(repo).keywordSearch(anyString(), anySet(), any(), isNull(), anyInt());
        clearInvocations(repo);
        service().search(new SearchRequest("사다리", Set.of(EvidenceKind.GUIDE), null, " 제조업 ", 3, false));
        verify(repo).vectorSearch(any(), anySet(), any(), eq("제조업"), anyInt());
        verify(repo).keywordSearch(anyString(), anySet(), any(), eq("제조업"), anyInt());
        clearInvocations(repo);
        service().search(new SearchRequest("사다리", Set.of(EvidenceKind.GUIDE), null, "  ", 3, false));
        verify(repo).keywordSearch(anyString(), anySet(), any(), isNull(), anyInt());
    }

    // 최종 리뷰 F13: 리랭커에 넘기기 전에 같은 지침(guideNo)을 걷는다 — 리랭커가 k건으로 자른 뒤
    // 걷으면 지침 카드가 k보다 적게 남는다
    @Test
    void 리랭크_경로도_지침_중복을_리랭크_전에_걷는다() {
        when(embedder.available()).thenReturn(true);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{1f}));
        List<ChunkHit> hits = new ArrayList<>();
        for (int i = 0; i < 4; i++) hits.add(guideHit(i + 1, "G-DUP"));
        hits.add(guideHit(5, "G-1"));
        hits.add(guideHit(6, "G-2"));
        when(repo.keywordSearch(anyString(), anySet(), any(), any(), anyInt())).thenReturn(hits);
        when(repo.findParents(anySet())).thenReturn(Map.of());
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<ChunkHit> in = inv.getArgument(1);
            int topK = inv.getArgument(2);
            return in.stream().limit(topK).map(h -> h.withScore(0.8)).toList();
        });
        List<Evidence> out = service().search(SearchRequest.guides("사다리", 3));
        assertThat(out).hasSize(3);
        assertThat(out.stream().map(e -> e.refKey().split("#")[0]).distinct().count()).isEqualTo(3);
    }

    // 리뷰 M1: 자르기(k*NO_RERANK_MULTIPLIER)를 지침(guideNo) 중복 제거보다 먼저 하면,
    // 상위권에 같은 지침이 몰려 있을 때 서로 다른 지침이 잘려나가 k건보다 적게 반환된다.
    @Test
    void 지침_중복제거는_잘라내기_전에_한다() {
        when(embedder.available()).thenReturn(false);
        List<ChunkHit> hits = new ArrayList<>();
        for (int i = 0; i < 5; i++) hits.add(guideHit(i + 1, "G-DUP"));   // rank0~4: 같은 지침 5건
        hits.add(guideHit(6, "G-1"));   // rank5
        hits.add(guideHit(7, "G-2"));   // rank6
        hits.add(guideHit(8, "G-3"));   // rank7
        when(repo.keywordSearch(anyString(), anySet(), any(), any(), anyInt())).thenReturn(hits);

        List<Evidence> out = service().search(SearchRequest.guides("사다리", 3).withoutRerank());

        assertThat(out).hasSize(3);
        long distinctGuides = out.stream().map(Evidence::refKey).map(k -> k.split("#")[0]).distinct().count();
        assertThat(distinctGuides).isEqualTo(3);
    }

    /**
     * H2 수정 — CASE_FATALITY(사진이 있는 유일한 종류)는 원본 데이터셋이 business 필드를
     * 주지 않아 전부 null이고 제조업 가산점을 못 받는다. 실제 DB에서는 이게 자바 쪽
     * 점수 경쟁이 아니라 <b>SQL {@code order by score desc limit ?} 단계에서부터</b>
     * 문제였다 — 위치 태그 질의에서 CASE_DISASTER의 원문 관련성이 워낙 높아 같은 SQL
     * 조회에 두 kind를 같이 태우면 CASE_FATALITY가 {@code wide}(=k×4) 안에 아예 들지
     * 못했다(실측: 2026-09-29 evidence_smoke.py B11=0/33, 설비 6개 전부 재현,
     * task-5-report.md). 그래서 이 테스트는 두 kind의 keywordSearch를 <b>서로 다른
     * mock</b>으로 걸어 그 상황을 재현한다 — CASE_DISASTER 쪽 mock에는 CASE_FATALITY
     * 후보가 아예 안 보인다(진짜 SQL이 상위 kind만 반환하는 것과 같다).
     */
    @Test
    void 사례_검색은_kind별_할당으로_사진_있는_사고사망을_포함한다() {
        when(embedder.available()).thenReturn(false);
        // CASE_DISASTER 조회 — business=제조업이라 가산점(+0.05축 +0.03업종)을 받는 4건뿐이다
        List<ChunkHit> disasterHits = new ArrayList<>();
        for (int i = 0; i < 4; i++) disasterHits.add(disasterHit(100 + i, "FALL", "제조업", false, 0));
        when(repo.keywordSearch(anyString(), eq(Set.of(EvidenceKind.CASE_DISASTER)), any(), any(), anyInt()))
                .thenReturn(disasterHits);
        // CASE_FATALITY 조회 — 독립된 SQL이라 CASE_DISASTER와 경쟁하지 않고 자기 몫의
        // wide 후보를 온전히 확보한다. 사진은 있지만 business=null이라 업종 가산점은 못 받는다
        List<ChunkHit> fatalityHits = List.of(
                caseHit(1, "FALL", null, true, 0),
                caseHit(2, "FALL", null, true, 0));
        when(repo.keywordSearch(anyString(), eq(Set.of(EvidenceKind.CASE_FATALITY)), any(), any(), anyInt()))
                .thenReturn(fatalityHits);

        List<Evidence> out = service().search(SearchRequest.cases("추락", AccidentType.FALL, "제조업", 4).withoutRerank());

        assertThat(out).hasSize(4);
        long fatalityCount = out.stream().filter(e -> e.kind() == EvidenceKind.CASE_FATALITY).count();
        assertThat(fatalityCount).isEqualTo(2);   // ceil(4/2) — 사진 있는 사고사망이 보장된 자리
        assertThat(out.stream().filter(e -> e.kind() == EvidenceKind.CASE_FATALITY).toList())
                .allSatisfy(e -> assertThat(e.thumbnailUrl()).isNotNull());
        long disasterCount = out.stream().filter(e -> e.kind() == EvidenceKind.CASE_DISASTER).count();
        assertThat(disasterCount).isEqualTo(2);   // floor(4/2)
    }
}
