package io.saife.evidence.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Equipment;
import io.saife.core.domain.Hazard;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 최종 리뷰 F6 — 프리페치는 시연 검색이 띄우는 카드부터, 그다음 축 기준으로 채운다 */
class MediaPrefetcherTest {
    private final EquipmentRepository equipments = mock(EquipmentRepository.class);
    private final HazardRepository hazards = mock(HazardRepository.class);
    private final EvidenceSearchService search = mock(EvidenceSearchService.class);
    private final PublicCaseRepository cases = mock(PublicCaseRepository.class);
    private final KoshaGuideRepository guides = mock(KoshaGuideRepository.class);
    private final MediaCache cache = mock(MediaCache.class);

    private static PublicCase photoCase(long id, AccidentType axis) {
        return PublicCase.builder().id(id).source("FATALITY").sourceKey("K" + id).keyword("k").contents("c")
                .accidentType(axis).imageUrl("https://www.kosha.or.kr/img/" + id + ".png").build();
    }

    private static Evidence caseCard(long refId) {
        return new Evidence(0, EvidenceKind.CASE_FATALITY, refId, "FATALITY:" + refId, "t", "s", null,
                "/api/media/case/" + refId + "/photo", null, Origin.CACHE, 0.9, OffsetDateTime.now(), Map.of());
    }

    @Test
    void 시연_검색_사진을_먼저_받고_시드_축_사례로_채운다() {
        when(equipments.findAll()).thenReturn(List.of(Equipment.builder().id(6L).name("고소작업대").build()));
        when(hazards.findByEquipmentIdOrderByCreatedAtDesc(6L))
                .thenReturn(List.of(Hazard.builder().equipmentId(6L).accidentType(AccidentType.FALL).build()));
        // 시연 검색은 사례 50만 띄운다
        when(search.search(any(SearchRequest.class))).thenAnswer(inv -> {
            SearchRequest r = inv.getArgument(0);
            return r.kinds().contains(EvidenceKind.CASE_FATALITY) ? List.of(caseCard(50)) : List.of();
        });
        PublicCase demo = photoCase(50, AccidentType.FALL);
        PublicCase sameAxis = photoCase(10, AccidentType.FALL);
        PublicCase otherAxis = photoCase(20, AccidentType.FIRE);   // 시드 축 밖 → 채우기 대상 아님
        when(cases.findById(50L)).thenReturn(Optional.of(demo));
        when(cases.findAll()).thenReturn(List.of(otherAxis, sameAxis, demo));
        when(guides.searchByName(anyString())).thenReturn(List.of());
        when(cache.fetch(anyString(), anyString(), eq("png"))).thenReturn(Optional.of(Path.of("x.png")));
        when(cache.thumbnail(any(), anyInt())).thenReturn(Optional.of(Path.of("x_320.png")));

        MediaPrefetcher.Result r = new MediaPrefetcher(equipments, hazards, search, cases, guides, cache).prefetchDemo();

        assertThat(r.demoPhotos()).isEqualTo(1);
        assertThat(r.photos()).isEqualTo(2);
        InOrder order = inOrder(cache);
        order.verify(cache).fetch(eq("case/50"), anyString(), eq("png"));
        order.verify(cache).fetch(eq("case/10"), anyString(), eq("png"));
        verify(cache, never()).fetch(eq("case/20"), anyString(), anyString());
        // 도구와 같은 모양의 질의(축 라벨 + 설비명)로 검색했다
        verify(search, atLeastOnce()).search(argThat(req -> "추락 고소작업대".equals(req.query())));
    }

    @Test
    void 지침은_시연_검색_결과를_먼저_받고_형식이_어긋난_번호는_건너뛴다() {
        when(equipments.findAll()).thenReturn(List.of(Equipment.builder().id(1L).name("이동식 사다리 A").build()));
        when(hazards.findByEquipmentIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(Hazard.builder().equipmentId(1L).accidentType(AccidentType.FALL).build()));
        Evidence guideCard = new Evidence(0, EvidenceKind.GUIDE, 7L, "G-1#0", "t", "s", null, null, null,
                Origin.CACHE, 0.9, OffsetDateTime.now(), Map.of("guideNo", "G-1"));
        when(search.search(any(SearchRequest.class))).thenAnswer(inv -> {
            SearchRequest r = inv.getArgument(0);
            return r.kinds().contains(EvidenceKind.GUIDE) ? List.of(guideCard) : List.of();
        });
        when(cases.findAll()).thenReturn(new ArrayList<>());
        KoshaGuide g1 = KoshaGuide.builder().id(7L).guideNo("G-1").guideName("사다리").fileDownloadUrl("https://portal.kosha.or.kr/f/1").build();
        KoshaGuide bad = KoshaGuide.builder().id(8L).guideNo("../x").guideName("사다리 2").fileDownloadUrl("https://portal.kosha.or.kr/f/2").build();
        when(guides.findById(7L)).thenReturn(Optional.of(g1));
        when(guides.searchByName(anyString())).thenReturn(List.of(g1, bad));
        when(cache.fetch(anyString(), anyString(), eq("pdf"))).thenReturn(Optional.of(Path.of("x.pdf")));

        MediaPrefetcher.Result r = new MediaPrefetcher(equipments, hazards, search, cases, guides, cache).prefetchDemo();

        assertThat(r.demoPdfs()).isEqualTo(1);
        assertThat(r.pdfs()).isEqualTo(1);   // G-1은 키워드 단계에서 다시 받지 않고, ../x는 건너뛴다
        verify(cache, times(1)).fetch(eq("guide/G-1"), anyString(), eq("pdf"));
        verify(cache, never()).fetch(startsWith("guide/.."), anyString(), anyString());
    }
}
