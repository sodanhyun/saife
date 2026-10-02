package io.saife.ai.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saife.common.config.DemoModeConfig;
import io.saife.common.service.SseService;
import io.saife.core.action.ActionService;
import io.saife.core.action.InspectionRecordStore;
import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Assessment;
import io.saife.core.domain.Hazard;
import io.saife.core.domain.HazardSource;
import io.saife.core.domain.RiskLevel;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.AssessmentRepository;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.PhotoRiskTable;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 같은 설비의 같은 위험요인은 표현이 달라도 재사용한다. 원래 등록된 위험요인이 이긴다 */
class VisionAssessmentServiceDedupTest {
    private final AssessmentRepository assessments = mock(AssessmentRepository.class);
    private final AssessmentHazardRepository links = mock(AssessmentHazardRepository.class);
    private final HazardRepository hazards = mock(HazardRepository.class);
    private final CandidateEvidenceCollector collector = mock(CandidateEvidenceCollector.class);
    private final ActionService actionService = mock(ActionService.class);

    @SuppressWarnings("unchecked")
    private final VisionAssessmentService service = new VisionAssessmentService(assessments, links, hazards,
            mock(VisionAnalyzer.class), new PhotoRiskTable(), mock(SseService.class), mock(DemoModeConfig.class),
            collector, actionService, mock(InspectionRecordStore.class), mock(EquipmentRepository.class),
            mock(ObjectProvider.class));

    private Hazard hazard(long id, String control, Boolean adopted, HazardSource source) {
        return Hazard.builder().id(id).siteId(1L).equipmentId(1L).accidentType(AccidentType.FALL)
                .missingControl(control).description("d").source(source)
                .aiSuggested(source == HazardSource.PHOTO).aiAdopted(adopted).build();
    }

    @Test
    void 사다리_장면은_원래_대장의_위험요인으로_재확인되고_제외된_후보는_재사용하지_않는다() {
        Hazard original = hazard(1L, "작업발판 미확보", true, HazardSource.PHOTO);
        Hazard excluded = hazard(30L, "최상부 디딤대 사용", false, HazardSource.PHOTO);
        Hazard laterDuplicate = hazard(31L, "최상부 발판 사용", true, HazardSource.PHOTO);
        // 최신순으로 온다
        when(hazards.findByEquipmentIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(laterDuplicate, excluded, original));
        when(assessments.findById(5L)).thenReturn(Optional.of(Assessment.builder().id(5L).siteId(1L).status("ANALYZED").build()));
        when(collector.forCandidate(any(), any())).thenReturn(List.of());
        when(actionService.findFor(any(), any())).thenReturn(Optional.empty());
        when(actionService.findPriorOpen(any(), any())).thenReturn(Optional.empty());

        VisionAssessmentService.AnalysisResult r = service.persist(5L, 1L, 1L, null,
                List.of(new VisionAnalyzer.Finding(AccidentType.FALL, "최상부 디딤대 사용", "사다리 맨 위 발판에 서 있음", 0.9)),
                "p.jpg");

        assertThat(r.candidates()).singleElement().satisfies(c -> {
            assertThat(c.hazardId()).isEqualTo(1L);
            assertThat(c.alreadyKnown()).isTrue();
            assertThat(c.missingControl()).isEqualTo("작업발판 미확보");
            assertThat(c.evidence()).isEqualTo("사다리 맨 위 발판에 서 있음");
            assertThat(c.riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(c.acceptable()).isFalse();
        });
        verify(hazards, never()).save(any(Hazard.class));
    }
}
