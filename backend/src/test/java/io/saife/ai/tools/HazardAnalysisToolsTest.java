package io.saife.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.ai.agent.AgentContextKeys;
import io.saife.common.service.SseService;
import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.live.*;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.evidence.service.LawArticleService;
import io.saife.publicapi.service.PublicApiCrawler;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

/** 도구 3·4·5 — 근거 계층 재배선 검증. #n 근거 줄, 원장 등록, query 없이도 동작을 확인한다 */
class HazardAnalysisToolsTest {
    private final EvidenceSearchService search = mock(EvidenceSearchService.class);
    private final LawArticleService laws = mock(LawArticleService.class);
    private final MsdsLiveClient msds = mock(MsdsLiveClient.class);
    private final EvidenceLedger ledger = mock(EvidenceLedger.class);
    private final PublicApiCrawler crawler = mock(PublicApiCrawler.class);
    private final SseService sse = mock(SseService.class);
    private final ToolContext ctx = new ToolContext(Map.of(AgentContextKeys.CONVERSATION_ID, "c1", AgentContextKeys.SESSION_ID, "s1"));

    private Evidence ev(int no, EvidenceKind k, String title, boolean image) {
        Map<String, Object> meta = new HashMap<>(); meta.put("hasImage", image);
        return new Evidence(no, k, 1L, k + ":" + no, title, "s", null, image ? "/api/media/case/1/photo" : null, null, Origin.CACHE, 0.84, OffsetDateTime.now(), meta);
    }

    private HazardAnalysisTools tools() {
        when(ledger.registerAll(eq("c1"), anyList())).thenAnswer(inv -> {
            List<Evidence> in = inv.getArgument(1);
            List<Evidence> out = new ArrayList<>();
            for (int i = 0; i < in.size(); i++) out.add(in.get(i).withNo(i + 1));
            return out;
        });
        return new HazardAnalysisTools(search, laws, msds, ledger, crawler, sse);
    }

    @Test
    void searchCases는_query로_검색하고_번호를_붙인다() {
        when(search.search(any(SearchRequest.class))).thenReturn(List.of(ev(0, EvidenceKind.CASE_FATALITY, "[협착] 스크류에 끼임", true)));
        when(crawler.checkLatest("FATALITY")).thenReturn(Optional.empty());
        String out = tools().searchCases("CAUGHT", "제조업", "설비 내부 청소 중 끼임", null, null, ctx);
        assertThat(out).contains("#1").contains("[사진]").contains("스크류에 끼임").contains("84%");
        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(cap.capture());
        // query에 축 라벨(협착)이 이미 없으므로 접두로 붙는다
        assertThat(cap.getValue().query()).isEqualTo("협착 설비 내부 청소 중 끼임");
        assertThat(cap.getValue().accidentType()).isEqualTo(AccidentType.CAUGHT);
        assertThat(cap.getValue().business()).isEqualTo("제조업");
    }

    @Test
    void query가_이미_축_라벨을_포함하면_중복으로_덧붙이지_않는다() {
        when(search.search(any(SearchRequest.class))).thenReturn(List.of(ev(0, EvidenceKind.CASE_FATALITY, "[협착] 스크류에 끼임", true)));
        when(crawler.checkLatest("FATALITY")).thenReturn(Optional.empty());
        tools().searchCases("CAUGHT", "제조업", "협착 설비 내부 청소 중 끼임", null, null, ctx);
        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(cap.capture());
        assertThat(cap.getValue().query()).isEqualTo("협착 설비 내부 청소 중 끼임");
    }

    @Test
    void query_없이도_동작() {
        when(search.search(any(SearchRequest.class))).thenReturn(List.of(ev(0, EvidenceKind.CASE_DISASTER, "[추락] 지붕", false)));
        when(crawler.checkLatest(anyString())).thenReturn(Optional.empty());
        String out = tools().searchCases("FALL", null, null, null, null, ctx);
        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(cap.capture());
        assertThat(cap.getValue().query()).isEqualTo("추락");   // 축 라벨로 대체
        assertThat(out).contains("#1");
    }

    @Test
    void query_없을때_설비_작업유형으로_대체질의를_만들고_위치는_쓰지_않는다() {
        when(search.search(any(SearchRequest.class))).thenReturn(List.of(ev(0, EvidenceKind.CASE_FATALITY, "[추락] 고소작업대에서 떨어짐", true)));
        when(crawler.checkLatest(anyString())).thenReturn(Optional.empty());
        // 재해 원문에는 사업장 위치가 없다 — 발생형태·설비·작업이 매칭 축이다
        String out = tools().searchCases("FALL", "제조업", null, "이동식 사다리 A", "페인트", ctx);
        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(cap.capture());
        assertThat(cap.getValue().query())
                .contains("추락").contains("이동식 사다리 A").contains("페인트")
                .doesNotContain("공장동").doesNotContain("차양부");
        assertThat(out).contains("#1");
    }

    @Test
    void analyzeHazards는_지침_벡터검색과_고정_조문을_붙인다() {
        when(search.search(any(SearchRequest.class))).thenReturn(List.of(ev(0, EvidenceKind.GUIDE, "[KOSHA GUIDE G-1] 사다리", false)));
        io.saife.evidence.domain.LawArticle a = io.saife.evidence.domain.LawArticle.builder().id(7L).lawId("L").lawName("산업안전보건기준에 관한 규칙").articleNo(42).articleSub(0).paragraphNo(1).title("추락의 방지").text("① 사업주는 …").sourceUrl("https://www.law.go.kr/x#J42:0").build();
        when(laws.get(anyString(), anyInt(), anyInt())).thenReturn(new Fetched<>(List.of(a), Origin.LIVE, OffsetDateTime.now(), null));
        String out = tools().analyzeHazards("도장", "천장", "이동식 사다리", "유성페인트", ctx);
        assertThat(out).contains("추락").contains("KOSHA GUIDE").contains("제42조").contains("#");
        verify(ledger, atLeastOnce()).registerAll(eq("c1"), anyList());
    }

    @Test
    void line은_키워드_폴백이면_유사도를_생략하고_캐시는_유지한다() {
        Evidence fallback = new Evidence(1, EvidenceKind.CASE_DISASTER, 1L, "CASE_DISASTER:1", "[추락] 지붕", "s",
                null, null, null, Origin.KEYWORD_FALLBACK, 0.39, OffsetDateTime.now(), Map.of());
        Evidence cached = new Evidence(2, EvidenceKind.CASE_FATALITY, 2L, "CASE_FATALITY:2", "[협착] 스크류", "s",
                null, null, null, Origin.CACHE, 0.84, OffsetDateTime.now(), Map.of());

        String fallbackLine = HazardAnalysisTools.line(fallback);
        String cachedLine = HazardAnalysisTools.line(cached);

        assertThat(fallbackLine).doesNotContain("유사도").contains("키워드 검색").contains("#1");
        assertThat(cachedLine).contains("유사도 84%").contains("캐시 ").contains("#2");
    }

    // 최종 리뷰 F2: 보정이 더해진 점수(1.07)가 와도 도구 줄은 100%를 넘기지 않는다
    @Test
    void line은_유사도를_100퍼센트로_자른다() {
        Evidence boosted = new Evidence(3, EvidenceKind.CASE_FATALITY, 3L, "CASE_FATALITY:3", "[추락] 지붕", "s",
                null, null, null, Origin.CACHE, 1.07, OffsetDateTime.now(), Map.of());
        assertThat(HazardAnalysisTools.line(boosted)).contains("유사도 100%").doesNotContain("107%");
    }

    @Test
    void 대화ID가_없으면_번호_없이_출력하고_원장을_건드리지_않는다() {
        ToolContext noConversation = new ToolContext(Map.of(AgentContextKeys.SESSION_ID, "s1"));
        when(search.search(any(SearchRequest.class))).thenReturn(List.of(ev(0, EvidenceKind.CASE_DISASTER, "[추락] 지붕", false)));
        when(crawler.checkLatest(anyString())).thenReturn(Optional.empty());

        String out = tools().searchCases("FALL", null, "지붕 위 작업", null, null, noConversation);

        assertThat(out).doesNotContain("#0").contains("유사 사고사례").contains("지붕");
        verify(ledger, never()).registerAll(anyString(), anyList());
    }

    @Test
    void getMsds는_라이브_결과와_픽토그램을_근거로() {
        MsdsLiveClient.MsdsBundle b = new MsdsLiveClient.MsdsBundle("001032", "톨루엔", "108-88-3", "1294", "GHS02,GHS07",
                Map.of("02", List.of("인화성 액체 : 구분2"), "08", List.of("TWA 50ppm")));
        when(msds.resolve("유성페인트")).thenReturn(new Fetched<>(b, Origin.LIVE, OffsetDateTime.now(), null));
        String out = tools().getMsds("유성페인트", ctx);
        assertThat(out).contains("톨루엔").contains("GHS02").contains("TWA 50ppm").contains("실시간").contains("#1");
        ArgumentCaptor<List<Evidence>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledger).registerAll(eq("c1"), cap.capture());
        assertThat(cap.getValue().get(0).kind()).isEqualTo(EvidenceKind.MSDS);
        assertThat(cap.getValue().get(0).meta()).containsEntry("pictograms", "GHS02,GHS07");
    }
}
