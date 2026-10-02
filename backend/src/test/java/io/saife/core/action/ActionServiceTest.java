package io.saife.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saife.common.error.ApiExceptions.ConflictException;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.AssessmentHazard;
import io.saife.core.domain.Hazard;
import io.saife.core.domain.HazardSource;
import io.saife.core.domain.RiskLevel;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.HazardRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** UC1 루프의 끝: 채택한 위험요인에만 대책을 걸고, 이행 완료는 멱등이다 */
class ActionServiceTest {

    private final ActionRepository actions = mock(ActionRepository.class);
    private final HazardRepository hazards = mock(HazardRepository.class);
    private final AssessmentHazardRepository links = mock(AssessmentHazardRepository.class);
    private final ActionService service = new ActionService(actions, hazards, links);

    private Hazard hazard(Boolean adopted) {
        return Hazard.builder().id(11L).siteId(1L).equipmentId(1L)
                .accidentType(AccidentType.PPE).missingControl("안전대 미착용").description("d")
                .source(HazardSource.PHOTO).aiSuggested(true).aiAdopted(adopted).build();
    }

    private AssessmentHazard link(long assessmentId) {
        return AssessmentHazard.builder().assessmentId(assessmentId).hazardId(11L)
                .riskLevel(RiskLevel.MEDIUM).ruleTrace("룰").build();
    }

    private ActionDtos.CreateActionRequest request(Long assessmentId) {
        return new ActionDtos.CreateActionRequest(assessmentId, "  안전대 지급, 착용 지도  ", "관리부",
                LocalDate.of(2026, 10, 16), "C-31-2017");
    }

    @Test
    void 채택한_위험요인에_PENDING_조치를_평가에_묶어_만든다() {
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));
        when(links.findHistoryByHazardId(11L)).thenReturn(List.of(link(40L)));
        when(actions.findByHazardId(11L)).thenReturn(List.of());
        when(actions.save(any(Action.class))).thenAnswer(inv -> inv.getArgument(0));

        ActionDtos.ActionView v = service.createForHazard(11L, request(40L));

        ArgumentCaptor<Action> saved = ArgumentCaptor.forClass(Action.class);
        verify(actions).save(saved.capture());
        assertThat(saved.getValue().getHazardId()).isEqualTo(11L);
        assertThat(saved.getValue().getAssessmentId()).isEqualTo(40L);
        assertThat(saved.getValue().getStatus()).isEqualTo(ActionStatus.PENDING);
        assertThat(saved.getValue().getContent()).isEqualTo("안전대 지급, 착용 지도");
        assertThat(saved.getValue().getGuideRef()).isEqualTo("C-31-2017");
        assertThat(v.equipmentId()).isEqualTo(1L);
        assertThat(v.dueDate()).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    void 평가를_비우면_가장_최근_평가에_묶는다() {
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));
        when(links.findHistoryByHazardId(11L)).thenReturn(List.of(link(7L), link(40L)));
        when(actions.findByHazardId(11L)).thenReturn(List.of());
        when(actions.save(any(Action.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.createForHazard(11L, request(null)).assessmentId()).isEqualTo(40L);
    }

    @Test
    void 채택_전이나_반려한_후보에는_대책을_걸_수_없다() {
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(null)));
        assertThatThrownBy(() -> service.createForHazard(11L, request(40L)))
                .isInstanceOf(ConflictException.class);

        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(false)));
        assertThatThrownBy(() -> service.createForHazard(11L, request(40L)))
                .isInstanceOf(ConflictException.class);
        verify(actions, never()).save(any());
    }

    @Test
    void 내용이_비면_400() {
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));
        assertThatThrownBy(() -> service.createForHazard(11L,
                new ActionDtos.CreateActionRequest(40L, " ", "관리부", null, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void 위험요인이_없는_평가를_지정하면_400() {
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));
        when(links.findHistoryByHazardId(11L)).thenReturn(List.of(link(7L)));
        assertThatThrownBy(() -> service.createForHazard(11L, request(40L)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void 같은_평가에_두_번_등록하면_409() {
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));
        when(links.findHistoryByHazardId(11L)).thenReturn(List.of(link(40L)));
        when(actions.findByHazardId(11L)).thenReturn(List.of(Action.builder().id(3L).hazardId(11L)
                .assessmentId(40L).content("x").status(ActionStatus.PENDING).build()));

        assertThatThrownBy(() -> service.createForHazard(11L, request(40L)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 이행_완료는_DONE과_완료_시각을_남긴다() {
        Action pending = Action.builder().id(3L).hazardId(11L).assessmentId(40L).content("x")
                .status(ActionStatus.PENDING).build();
        when(actions.findById(3L)).thenReturn(Optional.of(pending));
        when(actions.save(any(Action.class))).thenAnswer(inv -> inv.getArgument(0));
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));

        ActionDtos.ActionView v = service.complete(3L);

        assertThat(v.status()).isEqualTo(ActionStatus.DONE);
        assertThat(v.completedAt()).isNotNull();
        assertThat(v.equipmentId()).isEqualTo(1L);
    }

    @Test
    void 이행_완료는_멱등이다_완료_시각을_바꾸지_않는다() {
        OffsetDateTime first = OffsetDateTime.parse("2026-10-02T10:00:00+09:00");
        Action done = Action.builder().id(3L).hazardId(11L).content("x")
                .status(ActionStatus.DONE).completedAt(first).build();
        when(actions.findById(3L)).thenReturn(Optional.of(done));
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));

        ActionDtos.ActionView v = service.complete(3L);

        assertThat(v.completedAt()).isEqualTo(first);
        verify(actions, never()).save(any());
    }

    @Test
    void 없는_조치는_404() {
        when(actions.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.complete(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 다른_평가의_미이행_조치를_가장_이른_기한으로_찾는다() {
        when(actions.findByHazardId(11L)).thenReturn(List.of(
                Action.builder().id(1L).hazardId(11L).assessmentId(1L).content("a")
                        .status(ActionStatus.OVERDUE).dueDate(LocalDate.of(2026, 8, 21)).build(),
                Action.builder().id(2L).hazardId(11L).assessmentId(1L).content("b")
                        .status(ActionStatus.DONE).dueDate(LocalDate.of(2026, 7, 1)).build(),
                Action.builder().id(3L).hazardId(11L).assessmentId(40L).content("c")
                        .status(ActionStatus.PENDING).dueDate(LocalDate.of(2026, 10, 16)).build()));

        assertThat(service.findPriorOpen(11L, 40L)).map(Action::getId).contains(1L);
        assertThat(service.findFor(11L, 40L)).map(Action::getId).contains(3L);
    }
}
