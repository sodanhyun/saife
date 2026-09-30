package io.saife.evidence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.live.Fetched;
import io.saife.evidence.live.LawClient;
import io.saife.evidence.live.LiveOrCache;
import io.saife.evidence.live.Origin;
import io.saife.evidence.repository.LawArticleRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 최종 리뷰 F10·F19 — LawArticleService.get 라이브 경로 */
class LawArticleServiceTest {
    private final LawClient client = mock(LawClient.class);
    private final LawArticleRepository repo = mock(LawArticleRepository.class);
    private final LiveOrCache loc = new LiveOrCache(Duration.ofSeconds(2), 3, Duration.ofSeconds(30));
    private final LawArticleService service = new LawArticleService(client, repo, loc);

    /** 조문 42(항 1개)와 인접 조문 43이 섞인 JO 응답 */
    private static final String MIXED = """
            {"법령":{"조문":{"조문단위":[
              {"조문번호":"42","조문시행일자":"20260601","조문제목":"추락의 방지","조문여부":"조문",
               "항":{"항번호":"①","항내용":"① 사업주는 추락 위험을 방지해야 한다."}},
              {"조문번호":"43","조문시행일자":"20260601","조문제목":"개구부","조문여부":"조문",
               "조문내용":"제43조 개구부 등의 방호"}
            ]}}}
            """;

    private static LawArticle cached(String text, LocalDate eff) {
        return LawArticle.builder().id(7L).lawId("L1").lawName("규칙").articleNo(42).articleSub(0).paragraphNo(1)
                .title("추락의 방지").text(text).effectiveOn(eff).build();
    }

    @Test
    void 라이브_결과를_요청한_조문으로_거르고_내용이_같으면_캐시를_다시_쓰지_않는다() throws Exception {
        when(client.keyPresent()).thenReturn(true);
        when(client.fetchArticleJson("규칙", 42, 0)).thenReturn(MIXED);
        when(repo.findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo("규칙", 42, 0))
                .thenReturn(List.of(cached("① 사업주는 추락 위험을 방지해야 한다.", LocalDate.of(2026, 6, 1))));

        Fetched<List<LawArticle>> f = service.get("규칙", 42, 0);

        assertThat(f.origin()).isEqualTo(Origin.LIVE);
        assertThat(f.value()).hasSize(1).allSatisfy(a -> assertThat(a.getArticleNo()).isEqualTo(42));
        verify(repo, never()).save(any());
        verify(repo, never()).delete(any());
    }

    @Test
    void 본문이_바뀌었으면_캐시를_갱신한다() throws Exception {
        when(client.keyPresent()).thenReturn(true);
        when(client.fetchArticleJson("규칙", 42, 0)).thenReturn(MIXED);
        when(repo.findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo("규칙", 42, 0))
                .thenReturn(List.of(cached("① 옛 문구", LocalDate.of(2025, 1, 1))));

        service.get("규칙", 42, 0);

        verify(repo, times(1)).save(argThat(a -> a.getArticleNo() == 42));
        verify(repo, never()).save(argThat(a -> a.getArticleNo() == 43));
    }

    @Test
    void 요청한_조문이_응답에_없으면_캐시로_내려간다() throws Exception {
        when(client.keyPresent()).thenReturn(true);
        when(client.fetchArticleJson("규칙", 44, 0)).thenReturn(MIXED);   // 42·43만 온다
        LawArticle c44 = LawArticle.builder().id(9L).lawId("L1").lawName("규칙").articleNo(44).articleSub(0)
                .paragraphNo(0).text("제44조").build();
        when(repo.findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo("규칙", 44, 0)).thenReturn(List.of(c44));

        Fetched<List<LawArticle>> f = service.get("규칙", 44, 0);

        assertThat(f.origin()).isEqualTo(Origin.CACHE);
        assertThat(f.value()).containsExactly(c44);
        verify(repo, never()).save(any());
    }
}
