package io.saife.ai.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.*;
import io.saife.evidence.search.*;
import io.saife.evidence.service.LawArticleService;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CandidateEvidenceCollectorTest {
    private Evidence ev(EvidenceKind k, boolean img) {
        Map<String, Object> m = new HashMap<>(); m.put("hasImage", img);
        return new Evidence(0, k, 1L, k + ":" + img, "t", "s", null, img ? "/api/media/case/1/photo" : null, img ? "/api/media/case/1/photo?w=320" : null, Origin.CACHE, 0.6, OffsetDateTime.now(), m);
    }

    @Test
    void 지침_조문_사진사례_순으로_최대_3건_리랭크_없이() {
        EvidenceSearchService search = mock(EvidenceSearchService.class);
        when(search.search(argThat(r -> r != null && r.kinds().contains(EvidenceKind.GUIDE)))).thenReturn(List.of(ev(EvidenceKind.GUIDE, false)));
        when(search.search(argThat(r -> r != null && r.kinds().contains(EvidenceKind.CASE_FATALITY)))).thenReturn(List.of(ev(EvidenceKind.CASE_FATALITY, false), ev(EvidenceKind.CASE_FATALITY, true)));
        LawArticleService laws = mock(LawArticleService.class);
        io.saife.evidence.domain.LawArticle a = io.saife.evidence.domain.LawArticle.builder().id(1L).lawId("L").lawName("산업안전보건기준에 관한 규칙").articleNo(42).articleSub(0).paragraphNo(1).text("①").build();
        when(laws.get(anyString(), anyInt(), anyInt())).thenReturn(new Fetched<>(List.of(a), Origin.CACHE, OffsetDateTime.now(), null));
        List<Evidence> out = new CandidateEvidenceCollector(search, laws).forCandidate(AccidentType.FALL, "안전난간 미설치");
        assertThat(out).extracting(Evidence::kind).containsExactly(EvidenceKind.GUIDE, EvidenceKind.LAW, EvidenceKind.CASE_FATALITY);
        assertThat(out).extracting(Evidence::no).containsExactly(1, 2, 3);
        assertThat(out.get(2).thumbnailUrl()).isNotNull();   // 사진(썸네일) 있는 사례 우선
        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search, times(2)).search(cap.capture());
        assertThat(cap.getAllValues()).allMatch(r -> !r.rerank());
    }

    // 수정 목록 B1 T6: 축이 비어 와도 NPE로 근거 전체를 잃지 않는다 — 빠진 조치 문구로 검색한다
    @Test
    void 축이_null이어도_빠진_조치로_검색한다() {
        EvidenceSearchService search = mock(EvidenceSearchService.class);
        when(search.search(any())).thenReturn(List.of(ev(EvidenceKind.GUIDE, false)));
        LawArticleService laws = mock(LawArticleService.class);
        List<Evidence> out = new CandidateEvidenceCollector(search, laws).forCandidate(null, "안전난간 미설치");
        assertThat(out).isNotEmpty();
        verify(search, atLeastOnce()).search(argThat(r -> r != null && "안전난간 미설치".equals(r.query())));
    }
}
