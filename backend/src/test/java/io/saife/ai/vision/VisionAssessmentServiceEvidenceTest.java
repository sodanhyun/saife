package io.saife.ai.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.common.config.DemoModeConfig;
import io.saife.common.service.SseService;
import io.saife.core.action.ActionService;
import io.saife.core.action.InspectionRecordStore;
import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Assessment;
import io.saife.core.domain.AssessmentHazard;
import io.saife.core.domain.Hazard;
import io.saife.core.domain.HazardSource;
import io.saife.core.domain.RiskLevel;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.AssessmentRepository;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.PhotoRiskTable;
import io.saife.core.service.RiskRuleEngine;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 최종 리뷰 F19(수정 목록 B1 T6) — UC1 후보 카드의 근거({@code evidenceItems})가 세 경로
 * (판독 저장 persist · 결과 재조회 result · 채택/반려 decideCandidate) 모두에서 채워지는지.
 */
class VisionAssessmentServiceEvidenceTest {
    private final AssessmentRepository assessments = mock(AssessmentRepository.class);
    private final AssessmentHazardRepository links = mock(AssessmentHazardRepository.class);
    private final HazardRepository hazards = mock(HazardRepository.class);
    private final PhotoRiskTable riskTable = mock(PhotoRiskTable.class);
    private final DemoModeConfig demo = mock(DemoModeConfig.class);
    private final CandidateEvidenceCollector collector = mock(CandidateEvidenceCollector.class);
    private final ActionService actionService = mock(ActionService.class);

    @SuppressWarnings("unchecked")
    private final VisionAssessmentService service = new VisionAssessmentService(assessments, links, hazards,
            mock(VisionAnalyzer.class), riskTable, mock(SseService.class), demo, collector, actionService,
            mock(InspectionRecordStore.class), mock(EquipmentRepository.class), mock(ObjectProvider.class));

    private final Evidence card = new Evidence(1, EvidenceKind.GUIDE, 3L, "G-1#0", "[KOSHA GUIDE G-1] 추락", "s",
            null, null, null, Origin.CACHE, 0.8, OffsetDateTime.now(), Map.of());

    private final Hazard hazard = Hazard.builder().id(11L).siteId(1L).equipmentId(6L)
            .accidentType(AccidentType.FALL).missingControl("안전난간 미설치").description("d")
            .source(HazardSource.PHOTO).aiSuggested(true).build();

    private void common() {
        when(collector.forCandidate(AccidentType.FALL, "안전난간 미설치")).thenReturn(List.of(card));
        when(riskTable.decide(any(), any())).thenReturn(new RiskRuleEngine.Decision(RiskLevel.HIGH, (short) 3, (short) 3, "룰"));
        when(assessments.findById(5L)).thenReturn(Optional.of(Assessment.builder().id(5L).siteId(1L).status("ANALYZED").build()));
    }

    @Test
    void persist는_후보마다_근거를_싣는다() {
        common();
        when(hazards.findByEquipmentIdOrderByCreatedAtDesc(6L)).thenReturn(List.of());
        when(hazards.save(any(Hazard.class))).thenReturn(hazard);

        VisionAssessmentService.AnalysisResult r = service.persist(5L, 1L, 6L, null,
                List.of(new VisionAnalyzer.Finding(AccidentType.FALL, "안전난간 미설치", "난간 없음", 0.9)), "p.jpg");

        assertThat(r.candidates()).hasSize(1);
        assertThat(r.candidates().get(0).evidenceItems()).containsExactly(card);
        // 감소대책 초안: 축과 빠진 조치로 고정 표에서 고르고, 지침 번호는 근거 카드의 지침에서 온다
        assertThat(r.candidates().get(0).suggestedAction().content()).contains("안전난간");
        assertThat(r.candidates().get(0).suggestedAction().guideRef()).isEqualTo("G-1");
        assertThat(r.candidates().get(0).action()).isNull();
    }

    @Test
    void persist는_등급_다음에_근거_순서로_진행을_알린다() {
        common();
        when(hazards.findByEquipmentIdOrderByCreatedAtDesc(6L)).thenReturn(List.of());
        when(hazards.save(any(Hazard.class))).thenReturn(hazard);
        List<String> phases = new java.util.ArrayList<>();

        service.persist(5L, 1L, 6L, null,
                List.of(new VisionAnalyzer.Finding(AccidentType.FALL, "안전난간 미설치", "난간 없음", 0.9)), "p.jpg",
                (phase, message) -> phases.add(phase));

        assertThat(phases).containsExactly(VisionAssessmentService.PHASE_GRADING, VisionAssessmentService.PHASE_EVIDENCE);
    }

    @Test
    void result는_재조회에서도_근거를_싣는다() {
        common();
        when(links.findByAssessmentId(5L)).thenReturn(List.of(AssessmentHazard.builder().assessmentId(5L).hazardId(11L)
                .riskLevel(RiskLevel.HIGH).ruleTrace("룰").build()));
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard));

        VisionAssessmentService.AnalysisResult r = service.result(5L);

        assertThat(r.candidates()).singleElement().satisfies(c -> assertThat(c.evidenceItems()).containsExactly(card));
    }

    @Test
    void decideCandidate는_채택_응답에도_근거를_싣는다() {
        common();
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard));
        when(links.findHistoryByHazardId(11L)).thenReturn(List.of());

        VisionAssessmentService.Candidate c = service.decideCandidate(11L, true);

        assertThat(c.evidenceItems()).containsExactly(card);
        verify(collector).forCandidate(AccidentType.FALL, "안전난간 미설치");
    }
}
