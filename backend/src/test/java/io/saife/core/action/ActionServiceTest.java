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

/** UC1 루프의 끝: 채택한 위험요인에만 대책을 걸고, 이행 확인은 증빙과 확인자, 개선 후 위험성을 받는다 */
class ActionServiceTest {

    private final ActionRepository actions = mock(ActionRepository.class);
    private final HazardRepository hazards = mock(HazardRepository.class);
    private final AssessmentHazardRepository links = mock(AssessmentHazardRepository.class);
    private final InspectionRecordStore records = mock(InspectionRecordStore.class);
    private final io.saife.common.service.UploadStore uploads = mock(io.saife.common.service.UploadStore.class);
    private final io.saife.ai.vision.ActionEvidenceChecker checker = mock(io.saife.ai.vision.ActionEvidenceChecker.class);
    private final ActionService service = new ActionService(actions, hazards, links, records, uploads, checker);

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
                LocalDate.of(2026, 10, 16), "C-31-2017", ControlPriority.PPE);
    }

    @Test
    void 채택한_위험요인에_PENDING_조치를_평가에_묶어_만든다() {
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));
        when(links.findHistoryByHazardId(11L)).thenReturn(List.of(link(40L)));
        when(actions.findByHazardId(11L)).thenReturn(List.of());
        when(actions.save(any(Action.class))).thenAnswer(inv -> inv.getArgument(0));

        ActionDtos.ActionView v = service.createForHazard(11L, request(40L));
        assertThat(v.priority()).isEqualTo(ControlPriority.PPE);

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
                new ActionDtos.CreateActionRequest(40L, " ", "관리부", null, null, null)))
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

    private Action pendingWithPhoto() {
        Action a = Action.builder().id(3L).hazardId(11L).assessmentId(40L).content("이동식 비계 사용")
                .status(ActionStatus.PENDING).build();
        a.attachEvidence("data/uploads/2026-10-08/a.jpg", null);
        return a;
    }

    private ActionDtos.VerifyActionRequest verifyReq(RiskLevel level) {
        return new ActionDtos.VerifyActionRequest("  이동식 비계 반입, 설치  ", "안전관리자 홍길동", level);
    }

    @Test
    void 이행_확인은_DONE과_확인자_개선_후_위험성을_남긴다() {
        when(actions.findById(3L)).thenReturn(Optional.of(pendingWithPhoto()));
        when(actions.save(any(Action.class))).thenAnswer(inv -> inv.getArgument(0));
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));

        ActionDtos.ActionView v = service.verify(3L, verifyReq(RiskLevel.LOW));

        assertThat(v.status()).isEqualTo(ActionStatus.DONE);
        assertThat(v.completedAt()).isNotNull();
        assertThat(v.verifiedBy()).isEqualTo("안전관리자 홍길동");
        assertThat(v.residualLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(v.resultNote()).isEqualTo("이동식 비계 반입, 설치");
        assertThat(v.evidenceUrl()).isEqualTo("/api/action/3/evidence");
        assertThat(v.equipmentId()).isEqualTo(1L);
    }

    @Test
    void 같은_위험요인의_다른_미이행_대책도_함께_닫는다() {
        Action target = pendingWithPhoto();
        Action replanned = Action.builder().id(5L).hazardId(11L).content("이동식 비계 또는 말비계로 작업발판 확보")
                .status(ActionStatus.PENDING).build();
        Action doneBefore = Action.builder().id(6L).hazardId(11L).content("x").status(ActionStatus.DONE)
                .completedAt(OffsetDateTime.parse("2026-01-02T10:00:00+09:00")).build();
        when(actions.findById(3L)).thenReturn(Optional.of(target));
        when(actions.save(any(Action.class))).thenAnswer(inv -> inv.getArgument(0));
        when(actions.findByHazardId(11L)).thenReturn(List.of(target, replanned, doneBefore));
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));

        service.verify(3L, verifyReq(RiskLevel.LOW));

        assertThat(replanned.getStatus()).isEqualTo(ActionStatus.DONE);
        assertThat(replanned.getVerifiedBy()).isEqualTo("안전관리자 홍길동");
        assertThat(replanned.getEvidencePath()).isEqualTo(target.getEvidencePath());
        assertThat(replanned.getResultNote()).startsWith("같은 위험요인 이행 확인");
        assertThat(doneBefore.getCompletedAt()).isEqualTo(OffsetDateTime.parse("2026-01-02T10:00:00+09:00"));
    }

    @Test
    void 증빙_사진이_없으면_이행_확인을_받지_않는다() {
        when(actions.findById(3L)).thenReturn(Optional.of(Action.builder().id(3L).hazardId(11L).content("x")
                .status(ActionStatus.PENDING).build()));
        assertThatThrownBy(() -> service.verify(3L, verifyReq(RiskLevel.LOW)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("증빙 사진");
        verify(actions, never()).save(any());
    }

    @Test
    void 확인자나_개선_후_위험성이_없으면_400() {
        when(actions.findById(3L)).thenReturn(Optional.of(pendingWithPhoto()));
        assertThatThrownBy(() -> service.verify(3L, new ActionDtos.VerifyActionRequest(null, " ", RiskLevel.LOW)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("확인자");
        assertThatThrownBy(() -> service.verify(3L, new ActionDtos.VerifyActionRequest(null, "홍길동", null)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("개선 후 위험성");
    }

    @Test
    void 개선_후_위험성이_상이면_이행_확인을_받지_않는다() {
        when(actions.findById(3L)).thenReturn(Optional.of(pendingWithPhoto()));
        assertThatThrownBy(() -> service.verify(3L, verifyReq(RiskLevel.HIGH)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("추가 개선대책");
        verify(actions, never()).save(any());
    }

    @Test
    void 이행_확인은_멱등이다_확인_시각을_바꾸지_않는다() {
        OffsetDateTime first = OffsetDateTime.parse("2026-10-02T10:00:00+09:00");
        Action done = Action.builder().id(3L).hazardId(11L).content("x")
                .status(ActionStatus.DONE).completedAt(first).build();
        when(actions.findById(3L)).thenReturn(Optional.of(done));
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));

        ActionDtos.ActionView v = service.verify(3L, verifyReq(RiskLevel.LOW));

        assertThat(v.completedAt()).isEqualTo(first);
        verify(actions, never()).save(any());
    }

    @Test
    void 없는_조치는_404() {
        when(actions.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify(99L, verifyReq(RiskLevel.LOW))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 증빙_사진을_저장하고_대조_결과를_남긴다() throws Exception {
        Action pending = Action.builder().id(3L).hazardId(11L).content("이동식 비계 사용")
                .status(ActionStatus.PENDING).build();
        when(actions.findById(3L)).thenReturn(Optional.of(pending));
        when(actions.save(any(Action.class))).thenAnswer(inv -> inv.getArgument(0));
        when(hazards.findById(11L)).thenReturn(Optional.of(hazard(true)));
        when(uploads.store(any(), any())).thenReturn("data/uploads/2026-10-08/b.jpg");
        var item = new io.saife.ai.vision.ActionEvidenceChecker.Item("안전난간",
                io.saife.ai.vision.ActionEvidenceChecker.ItemStatus.SEEN, "난간대 보임");
        when(checker.check(any(), any(), any(), any())).thenReturn(new io.saife.ai.vision.ActionEvidenceChecker.Result(
                io.saife.ai.vision.ActionEvidenceChecker.Verdict.CONFIRMED, List.of(item)));

        ActionDtos.ActionView v = service.attachEvidence(3L, new byte[]{1, 2}, "image/jpeg", "a.jpg");

        assertThat(v.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(v.evidenceUrl()).isEqualTo("/api/action/3/evidence");
        assertThat(v.photoCheck().verdict()).isEqualTo(io.saife.ai.vision.ActionEvidenceChecker.Verdict.CONFIRMED);
        assertThat(v.photoCheck().items()).extracting(io.saife.ai.vision.ActionEvidenceChecker.Item::item)
                .containsExactly("안전난간");
    }

    @Test
    void 사진이_아닌_파일과_확인된_조치에는_증빙을_붙이지_않는다() {
        when(actions.findById(3L)).thenReturn(Optional.of(pendingWithPhoto()));
        assertThatThrownBy(() -> service.attachEvidence(3L, new byte[]{1}, "application/pdf", "a.pdf"))
                .isInstanceOf(InvalidRequestException.class);
        Action done = Action.builder().id(4L).hazardId(11L).content("x").status(ActionStatus.DONE).build();
        when(actions.findById(4L)).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.attachEvidence(4L, new byte[]{1}, "image/jpeg", "a.jpg"))
                .isInstanceOf(ConflictException.class);
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
