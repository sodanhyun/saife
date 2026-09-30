package io.saife.workplan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.Equipment;
import io.saife.core.domain.Hazard;
import io.saife.core.domain.HazardSource;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.live.Origin;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.publicapi.repository.MsdsCacheRepository;
import io.saife.publicapi.service.MsdsResolver;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * B1 T4 fix round 1 (ruling R53) — {@link BriefingComposer#compose}의 유사 사고사례 절.
 *
 * <p>실 {@link RiskRuleEngine}을 그대로 쓴다(빈 슬롯 입력에서도 예외 없이 기본값을 낸다) —
 * 룰 엔진 판정문 자체는 이 테스트의 관심사가 아니다.
 */
class BriefingComposerTest {
    private final EquipmentRepository equipmentRepository = mock(EquipmentRepository.class);
    private final HazardRepository hazardRepository = mock(HazardRepository.class);
    private final AssessmentHazardRepository assessmentHazardRepository = mock(AssessmentHazardRepository.class);
    private final ActionRepository actionRepository = mock(ActionRepository.class);
    private final WorkPlanSlotRepository workPlanSlotRepository = mock(WorkPlanSlotRepository.class);
    private final MsdsCacheRepository msdsCacheRepository = mock(MsdsCacheRepository.class);
    private final MsdsResolver msdsResolver = mock(MsdsResolver.class);
    private final EvidenceSearchService evidenceSearchService = mock(EvidenceSearchService.class);
    private final EvidenceLedger evidenceLedger = mock(EvidenceLedger.class);

    private BriefingComposer composer() {
        return new BriefingComposer(equipmentRepository, hazardRepository, assessmentHazardRepository,
                actionRepository, workPlanSlotRepository, msdsCacheRepository, msdsResolver,
                new RiskRuleEngine(), evidenceSearchService, evidenceLedger);
    }

    private WorkPlan plan() {
        return WorkPlan.builder().id(10L).siteId(1L).equipmentId(5L).conversationId("c1")
                .workName("사다리 위 도장").workPlace("공장동 후면 차양부").workDate(LocalDate.now()).build();
    }

    private void stubCommon() {
        when(equipmentRepository.findById(5L)).thenReturn(Optional.of(
                Equipment.builder().id(5L).siteId(1L).name("이동식 사다리 A").build()));
        when(hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(5L)).thenReturn(List.of(
                Hazard.builder().id(1L).siteId(1L).equipmentId(5L).accidentType(AccidentType.FALL)
                        .description("추락").source(HazardSource.MANUAL).build()));
        when(actionRepository.findPendingByHazardIds(anyList(), eq(ActionStatus.DONE))).thenReturn(List.of());
        when(workPlanSlotRepository.findByWorkPlanId(anyLong())).thenReturn(List.of());
    }

    private Evidence evidence(String refKey, String title) {
        return new Evidence(0, EvidenceKind.CASE_FATALITY, 1L, refKey, title, "s", null, null, null,
                Origin.CACHE, 0.8, OffsetDateTime.now(), Map.of());
    }

    @Test
    void 원장에_있는_카드만_번호를_병기한다() {
        stubCommon();
        Evidence inLedger = evidence("F:1", "사례A");
        Evidence notInLedger = evidence("F:2", "사례B");
        when(evidenceSearchService.search(any(SearchRequest.class))).thenReturn(List.of(inLedger, notInLedger));
        when(evidenceLedger.all("c1")).thenReturn(List.of(inLedger.withNo(3)));

        String out = composer().compose(plan());

        // "[#n]"이어야 프론트 CitationChip이 칩으로 그린다(맨 "#n"은 텍스트로만 남는다)
        assertThat(out).contains("- [#3] 사례A");
        assertThat(out).contains("- 사례B");
        assertThat(out).doesNotContain("#4").doesNotContain("- [#3] 사례B").doesNotContain("- #3 ");
    }

    @Test
    void 질의는_축라벨과_설비작업명을_담고_위치태그는_없다() {
        stubCommon();
        when(evidenceSearchService.search(any(SearchRequest.class))).thenReturn(List.of());

        composer().compose(plan());

        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(evidenceSearchService, atLeastOnce()).search(cap.capture());
        SearchRequest first = cap.getAllValues().get(0);
        assertThat(first.query()).contains("추락").contains("이동식 사다리 A").contains("사다리 위 도장");
        assertThat(first.query()).doesNotContain("공장동").doesNotContain("차양부");
        assertThat(first.accidentType()).isEqualTo(AccidentType.FALL);
    }

    @Test
    void 제조업_먼저_빈결과면_전체업종으로_재시도한다() {
        // 같은 목 메서드에 argThat() 스텁을 두 번 등록하면, 두 번째 등록을 평가하는 시점에
        // Mockito가 첫 번째 등록의 매처를 "지금 등록 중인 호출(더미 null 인자)"에 대고
        // 재평가하면서 NPE를 낸다 — 그래서 분기 하나를 thenAnswer로 묶는다.
        stubCommon();
        Evidence hit = evidence("F:1", "사례A");
        when(evidenceSearchService.search(any(SearchRequest.class))).thenAnswer(inv -> {
            SearchRequest r = inv.getArgument(0);
            return "제조업".equals(r.business()) ? List.of() : List.of(hit);
        });
        when(evidenceLedger.all("c1")).thenReturn(List.of());

        String out = composer().compose(plan());

        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(evidenceSearchService, times(2)).search(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(r -> "제조업".equals(r.business()));
        assertThat(cap.getAllValues()).anyMatch(r -> r.business() == null);
        assertThat(out).contains("사례A");
    }

    @Test
    void 검색이_실패해도_브리핑은_유사사례_절_없이_생성된다() {
        stubCommon();
        when(evidenceSearchService.search(any(SearchRequest.class))).thenThrow(new RuntimeException("검색 장애"));

        String out = composer().compose(plan());

        assertThat(out).doesNotContain("[유사 사고사례]");
        assertThat(out).contains("작업: 사다리 위 도장");
    }
}
