package io.saife.workplan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;
import io.saife.core.repository.EquipmentRepository;
import io.saife.evidence.repository.WorkPlanEvidenceRepository;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.domain.WorkPlanWorker;
import io.saife.workplan.dto.WorkPlanDtos;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import io.saife.workplan.repository.WorkPlanWorkerRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 관리감독자 승인 규칙: 상 판정이 있고 대책이 미이행이면 잠정조치 없이는 승인하지 않는다(고시 제12조제4항).
 */
class WorkPlanServiceApprovalTest {

    private final WorkPlanRepository workPlanRepository = mock(WorkPlanRepository.class);
    private final WorkPlanSlotRepository slotRepository = mock(WorkPlanSlotRepository.class);
    private final WorkPlanWorkerRepository workerRepository = mock(WorkPlanWorkerRepository.class);
    private final EquipmentRepository equipmentRepository = mock(EquipmentRepository.class);
    private final WorkPlanEvidenceRepository evidenceRepository = mock(WorkPlanEvidenceRepository.class);
    private final BriefingViewBuilder viewBuilder = mock(BriefingViewBuilder.class);
    private final IncidentRepository incidentRepository = mock(IncidentRepository.class);

    private final WorkPlanService service = new WorkPlanService(workPlanRepository, slotRepository, workerRepository,
            equipmentRepository, evidenceRepository, new ObjectMapper(), viewBuilder, incidentRepository);

    private WorkPlan submitted() {
        WorkPlan plan = WorkPlan.builder().id(7L).siteId(1L).workName("천장 페인트 작업").workDate(LocalDate.now())
                .briefing("TBM").status(WorkPlanStatus.SUBMITTED).build();
        when(workPlanRepository.findById(7L)).thenReturn(Optional.of(plan));
        when(workPlanRepository.save(any(WorkPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        return plan;
    }

    private WorkPlanDtos.BriefingView view(boolean interimRequired) {
        WorkPlanDtos.HazardDecision fall = new WorkPlanDtos.HazardDecision(AccidentType.FALL, "떨어짐",
                interimRequired ? RiskLevel.HIGH : RiskLevel.MEDIUM, (short) 3, (short) 3, "근거",
                interimRequired ? "이동식 비계(안전난간) 또는 말비계로 작업발판 확보 (제42조제1항)" : null);
        return new WorkPlanDtos.BriefingView(List.of(), List.of(fall), null, List.of(), List.of(), interimRequired, List.of());
    }

    @Test
    void 상_판정에_대책이_미이행이면_잠정조치_없이_승인하지_않는다() {
        WorkPlan plan = submitted();
        when(viewBuilder.compute(plan)).thenReturn(view(true));

        assertThatThrownBy(() -> service.approve(7L, "홍길동", null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("잠정조치");
        assertThatThrownBy(() -> service.approve(7L, "홍길동", "   "))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(plan.getStatus()).isEqualTo(WorkPlanStatus.SUBMITTED);
        verify(workPlanRepository, never()).save(any(WorkPlan.class));
    }

    @Test
    void 잠정조치를_적으면_조건부_승인이고_승인자_이름이_남는다() {
        WorkPlan plan = submitted();
        when(viewBuilder.compute(plan)).thenReturn(view(true));

        service.approve(7L, "김철수", "2인 1조, 아웃트리거 고정 후 맨 위 두 칸 사용 금지");

        assertThat(plan.getStatus()).isEqualTo(WorkPlanStatus.CONDITIONAL);
        assertThat(plan.getApprovedBy()).isEqualTo("김철수");
        assertThat(plan.getApprovalNote()).isEqualTo("2인 1조, 아웃트리거 고정 후 맨 위 두 칸 사용 금지");
        assertThat(plan.getApprovedAt()).isNotNull();
    }

    @Test
    void 상_판정이_없으면_조건_없이_승인되고_승인자가_없으면_관리감독자로_남는다() {
        WorkPlan plan = submitted();
        when(viewBuilder.compute(plan)).thenReturn(view(false));

        service.approve(7L, null, null);

        assertThat(plan.getStatus()).isEqualTo(WorkPlanStatus.APPROVED);
        assertThat(plan.getApprovedBy()).isEqualTo("관리감독자");
    }

    @Test
    void 작업_보류_중인_점검표는_승인하지_않는다() {
        WorkPlan plan = submitted();
        plan.hold();

        assertThatThrownBy(() -> service.approve(7L, "홍길동", "잠정조치"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 잠정조치가_작업_금지면_승인하지_않고_보류로_안내한다() {
        WorkPlan plan = submitted();
        when(viewBuilder.compute(plan)).thenReturn(view(true));

        assertThatThrownBy(() -> service.approve(7L, "김철수", "이동식 비계 설치 전까지 사다리 작업 금지"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("작업 보류");
        assertThatThrownBy(() -> service.approve(7L, "김철수", "기존 대책 이행 전까지 해당 작업 중지"))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(plan.getStatus()).isEqualTo(WorkPlanStatus.SUBMITTED);
    }

    @Test
    void 보류하면_사유가_경고로_남고_보류_해제는_승인_대기로_돌린다() {
        WorkPlan plan = WorkPlan.builder().id(7L).siteId(1L).equipmentId(1L).workName("천장 페인트 작업")
                .workDate(LocalDate.now()).briefing("TBM").status(WorkPlanStatus.SUBMITTED).build();
        when(workPlanRepository.findById(7L)).thenReturn(Optional.of(plan));
        when(workPlanRepository.save(any(WorkPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.hold(7L, "이동식 비계 설치 전까지 사다리 작업 금지");
        assertThat(plan.getStatus()).isEqualTo(WorkPlanStatus.HOLD);
        assertThat(plan.getWarningNote()).isEqualTo("작업 보류: 이동식 비계 설치 전까지 사다리 작업 금지");

        when(workPlanRepository.findByEquipmentIdOrderByWorkDateDesc(1L)).thenReturn(List.of(plan));
        assertThat(service.releaseHolds(1L)).isEqualTo(1);
        assertThat(plan.getStatus()).isEqualTo(WorkPlanStatus.SUBMITTED);
        assertThat(plan.getWarningNote()).isNull();
        assertThat(plan.getApprovedBy()).isNull();
    }

    @Test
    void 승인자를_비우면_작업_담당_반장이_승인자다() {
        WorkPlan plan = submitted();
        when(viewBuilder.compute(plan)).thenReturn(view(false));
        when(workerRepository.findByWorkPlanId(7L)).thenReturn(List.of(
                WorkPlanWorker.builder().workPlanId(7L).name("이영희").build(),
                WorkPlanWorker.builder().workPlanId(7L).name("김철수").position("반장").build()));

        service.approve(7L, null, null);

        assertThat(plan.getApprovedBy()).isEqualTo("김철수");
    }

    @Test
    void 오류_문구에_내부_ID가_없다() {
        when(workPlanRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(99L)).hasMessage("점검 기록을 찾을 수 없습니다.");
    }
}
