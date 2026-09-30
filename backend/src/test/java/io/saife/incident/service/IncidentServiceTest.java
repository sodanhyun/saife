package io.saife.incident.service;

import io.saife.core.domain.AccidentType;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.dto.IncidentDtos;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사고 연쇄(2-2) — {@link IncidentService#register}가 만드는 {@code cascade}·
 * {@code affectedWorkPlans}를 검증한다.
 *
 * <p>시드(V2)의 설비 4(유압 프레스 3호)는 위험요인 5(협착)만 있고 평가·조치 이력이
 * 전혀 없다 — "예고되지 않은 사고" 시나리오를 만들기 좋은 깨끗한 설비라 대부분의
 * 테스트가 여기를 쓴다. 설비 1(이동식 사다리 A)·6(고소작업대)은 예고/미이행 시나리오에 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IncidentServiceTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private WorkPlanRepository workPlanRepository;

    private static final Long SITE_ID = 1L;

    private IncidentDtos.RegisterRequest request(Long equipmentId, OffsetDateTime occurredAt,
                                                 Integer leaveDays, AccidentType accidentType) {
        return new IncidentDtos.RegisterRequest(equipmentId, null, null, occurredAt,
                "홍OO", IncidentSeverity.LOST_TIME, leaveDays, accidentType, "테스트용 사고 서술");
    }

    private WorkPlan savePlan(Long equipmentId, LocalDate workDate, WorkPlanStatus status, String existingWarning) {
        return workPlanRepository.save(WorkPlan.builder()
                .siteId(SITE_ID)
                .equipmentId(equipmentId)
                .workName("테스트 작업")
                .workDate(workDate)
                .status(status)
                .warningNote(existingWarning)
                .build());
    }

    // ────────────────────────── affectedWorkPlans 필터 ──────────────────────────

    @Test
    @DisplayName("affectedWorkPlans — 사고일 이전 작업계획서는 제외하고, 사고일 당일·이후만 담는다")
    void affectedWorkPlansExcludesPlansBeforeIncidentDate() {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        LocalDate incidentDate = occurredAt.toLocalDate();

        WorkPlan before = savePlan(4L, incidentDate.minusDays(1), WorkPlanStatus.SUBMITTED, null);
        WorkPlan sameDay = savePlan(4L, incidentDate, WorkPlanStatus.SUBMITTED, null);
        WorkPlan wrongStatus = savePlan(4L, incidentDate.plusDays(1), WorkPlanStatus.DRAFT, null);
        WorkPlan wrongEquipment = savePlan(5L, incidentDate.plusDays(1), WorkPlanStatus.APPROVED, null);

        IncidentDtos.RegisterResponse response =
                incidentService.register(SITE_ID, request(4L, occurredAt, 5, AccidentType.STRUCK));

        assertThat(response.affectedWorkPlans()).hasSize(1);
        assertThat(response.affectedWorkPlans().get(0).workPlanId()).isEqualTo(sameDay.getId());

        assertThat(workPlanRepository.findById(before.getId()).orElseThrow().getWarningNote())
                .as("사고일 이전 계획서는 경고가 붙지 않는다").isNull();
        assertThat(workPlanRepository.findById(wrongStatus.getId()).orElseThrow().getWarningNote())
                .as("DRAFT 상태는 대상이 아니다").isNull();
        assertThat(workPlanRepository.findById(wrongEquipment.getId()).orElseThrow().getWarningNote())
                .as("다른 설비는 대상이 아니다").isNull();
    }

    @Test
    @DisplayName("warning_note — 기존 경고가 있으면 줄바꿈으로 이어붙이고, approvalNote는 건드리지 않는다")
    void warningNoteAppendsWithNewlineAndDoesNotTouchApprovalNote() {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        LocalDate incidentDate = occurredAt.toLocalDate();
        String existing = "이전 사고가 남긴 경고";

        WorkPlan plan = savePlan(4L, incidentDate, WorkPlanStatus.APPROVED, existing);
        plan.approve("관리자", "조건부 승인 메모");
        workPlanRepository.save(plan);

        IncidentDtos.RegisterResponse response =
                incidentService.register(SITE_ID, request(4L, occurredAt, 5, AccidentType.CAUGHT));

        IncidentDtos.AffectedWorkPlan affected = response.affectedWorkPlans().get(0);
        assertThat(affected.warning()).contains("이 설비에서").contains("협착").contains("확인");
        assertThat(affected.warning()).as("응답의 warning은 새로 붙인 문구만 담는다").doesNotContain(existing);

        WorkPlan reloaded = workPlanRepository.findById(plan.getId()).orElseThrow();
        assertThat(reloaded.getWarningNote()).isEqualTo(existing + "\n" + affected.warning());
        assertThat(reloaded.getApprovalNote()).as("승인 메모는 그대로 유지된다").isEqualTo("조건부 승인 메모");
    }

    // ────────────────────────── cascade 4단계 ──────────────────────────

    @Test
    @DisplayName("cascade — 4단계가 order 1~4 순서로, RECALL은 예고된 사고라 CRITICAL")
    void cascadeStepsInOrderWithRecallCriticalWhenPredicted() {
        // 설비 1(이동식 사다리 A): hazard1(FALL)이 미이행 조치(action1, OVERDUE)와 함께 있다
        OffsetDateTime occurredAt = OffsetDateTime.now();

        IncidentDtos.RegisterResponse response =
                incidentService.register(SITE_ID, request(1L, occurredAt, 14, AccidentType.FALL));

        List<IncidentDtos.CascadeStep> cascade = response.cascade();
        assertThat(cascade).hasSize(4);
        assertThat(cascade.get(0).order()).isEqualTo(1);
        assertThat(cascade.get(1).order()).isEqualTo(2);
        assertThat(cascade.get(2).order()).isEqualTo(3);
        assertThat(cascade.get(3).order()).isEqualTo(4);
        assertThat(cascade.stream().map(IncidentDtos.CascadeStep::kind).toList())
                .containsExactly("RECALL", "FOLLOW_UP", "REPORT", "WORK_PLAN");

        IncidentDtos.CascadeStep recallStep = cascade.get(0);
        assertThat(recallStep.emphasis()).isEqualTo(TimelineDtos.Emphasis.CRITICAL);
        assertThat(recallStep.refId()).isEqualTo(1L);
        assertThat(recallStep.refType()).isEqualTo("EQUIPMENT");
        // detail은 recall.headline()으로 시작한다. 유사 사례가 검색되면(B1 Task 5)
        // " · 동종 유사 사고 N건(사진 M)"이 뒤에 덧붙는다 — 검색 결과 유무에 관계없이 통과해야 한다.
        assertThat(recallStep.detail()).startsWith(response.recall().headline());
        if (!response.similarCases().isEmpty()) {
            assertThat(recallStep.detail()).contains("동종 유사 사고 " + response.similarCases().size() + "건");
        }

        IncidentDtos.CascadeStep followUpStep = cascade.get(1);
        assertThat(followUpStep.emphasis()).isEqualTo(TimelineDtos.Emphasis.WARNING);
        assertThat(followUpStep.title()).contains("#" + response.followUp().assessmentId());
        assertThat(followUpStep.refType()).isEqualTo("ASSESSMENT");

        // occurredAt이 지금이라 제출 기한(발생일+1개월)이 아직 한참 남았다 → WARNING(의무는 있음, 3일 이내 아님)
        IncidentDtos.CascadeStep reportStep = cascade.get(2);
        assertThat(reportStep.emphasis()).isEqualTo(TimelineDtos.Emphasis.WARNING);
        assertThat(reportStep.refType()).isEqualTo("INCIDENT");

        // 이 테스트에서 설비 1에 진행 중 작업계획서를 만들지 않았으므로 0건 → NORMAL
        IncidentDtos.CascadeStep workPlanStep = cascade.get(3);
        assertThat(workPlanStep.emphasis()).isEqualTo(TimelineDtos.Emphasis.NORMAL);
        assertThat(workPlanStep.detail()).isEqualTo("해당 없음");
        assertThat(workPlanStep.refId()).isNull();
    }

    @Test
    @DisplayName("cascade RECALL — 예고되진 않았지만 미이행 조치가 있으면 WARNING")
    void cascadeRecallIsWarningWhenUnfinishedActionsExistButNotPredicted() {
        // 설비 6(고소작업대): hazard7(FALL)에 미이행 조치(action5, PENDING)가 있다.
        // 사고 축을 PPE로 줘서 predicted는 false가 되게 한다.
        OffsetDateTime occurredAt = OffsetDateTime.now();

        IncidentDtos.RegisterResponse response =
                incidentService.register(SITE_ID, request(6L, occurredAt, 5, AccidentType.PPE));

        assertThat(response.recall().predicted()).isFalse();
        assertThat(response.cascade().get(0).emphasis()).isEqualTo(TimelineDtos.Emphasis.WARNING);
    }

    @Test
    @DisplayName("cascade RECALL — 예고도, 미이행 조치도 없으면 NORMAL")
    void cascadeRecallIsNormalWhenNoPredictionAndNoUnfinishedActions() {
        // 설비 4(유압 프레스 3호): hazard5(협착)만 있고 조치·평가 이력이 없다.
        // 사고 축을 STRUCK으로 줘서 hazard5(CAUGHT)와 겹치지 않게 한다.
        OffsetDateTime occurredAt = OffsetDateTime.now();

        IncidentDtos.RegisterResponse response =
                incidentService.register(SITE_ID, request(4L, occurredAt, 5, AccidentType.STRUCK));

        assertThat(response.recall().predicted()).isFalse();
        assertThat(response.recall().unfinishedActions()).isEmpty();
        assertThat(response.cascade().get(0).emphasis()).isEqualTo(TimelineDtos.Emphasis.NORMAL);
    }

    @Test
    @DisplayName("cascade REPORT — 기한이 3일 이내면 CRITICAL")
    void cascadeReportIsCriticalWhenDueDateWithinThreeDays() {
        // 발생일 + 1개월이 오늘 + 2일이 되도록 역산한다
        OffsetDateTime occurredAt = OffsetDateTime.now().minusMonths(1).plusDays(2);

        IncidentDtos.RegisterResponse response =
                incidentService.register(SITE_ID, request(4L, occurredAt, 5, AccidentType.STRUCK));

        assertThat(response.reportDuty().daysRemaining()).isLessThanOrEqualTo(3L);
        IncidentDtos.CascadeStep reportStep = response.cascade().get(2);
        assertThat(reportStep.emphasis()).isEqualTo(TimelineDtos.Emphasis.CRITICAL);
        assertThat(reportStep.detail()).startsWith("D-");
    }

    @Test
    @DisplayName("cascade REPORT — 휴업 3일 미만이면 제출 의무가 없어 NORMAL")
    void cascadeReportIsNormalWhenNoReportingDuty() {
        OffsetDateTime occurredAt = OffsetDateTime.now();

        IncidentDtos.RegisterResponse response =
                incidentService.register(SITE_ID, request(4L, occurredAt, 1, AccidentType.STRUCK));

        assertThat(response.reportDuty().dueDate()).isNull();
        IncidentDtos.CascadeStep reportStep = response.cascade().get(2);
        assertThat(reportStep.emphasis()).isEqualTo(TimelineDtos.Emphasis.NORMAL);
        assertThat(reportStep.detail()).isEqualTo("제출 의무 없음");
    }

    @Test
    @DisplayName("cascade WORK_PLAN — 진행 중 작업계획서가 있으면 n건 · WARNING, 첫 건이 refId")
    void cascadeWorkPlanIsWarningWhenAffectedPlansExist() {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        LocalDate incidentDate = occurredAt.toLocalDate();

        WorkPlan plan = savePlan(4L, incidentDate, WorkPlanStatus.SUBMITTED, null);

        IncidentDtos.RegisterResponse response =
                incidentService.register(SITE_ID, request(4L, occurredAt, 5, AccidentType.STRUCK));

        IncidentDtos.CascadeStep workPlanStep = response.cascade().get(3);
        assertThat(workPlanStep.emphasis()).isEqualTo(TimelineDtos.Emphasis.WARNING);
        assertThat(workPlanStep.title()).contains("1건");
        assertThat(workPlanStep.refId()).isEqualTo(plan.getId());
        assertThat(workPlanStep.refType()).isEqualTo("WORK_PLAN");
        assertThat(workPlanStep.detail()).contains("테스트 작업");
    }

    // ────────────────────────── detail() 재조회 (최종 리뷰 F9) ──────────────────────────

    @Test
    @DisplayName("detail — 재조회해도 수시평가 단계가 등록 때와 같은 등급 변화를 말하고, 경고 없는 계획서는 빠진다")
    void detailReconstructsFollowUpAndSkipsPlansWithoutWarning() {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        LocalDate incidentDate = occurredAt.toLocalDate();
        WorkPlan warned = savePlan(4L, incidentDate, WorkPlanStatus.SUBMITTED, null);

        IncidentDtos.RegisterResponse registered =
                incidentService.register(SITE_ID, request(4L, occurredAt, 5, AccidentType.CAUGHT));
        // 사고 뒤에 새로 만든 계획서 — 경고가 붙은 적이 없다
        WorkPlan later = savePlan(4L, incidentDate.plusDays(1), WorkPlanStatus.SUBMITTED, null);

        IncidentDtos.RegisterResponse reloaded = incidentService.detail(registered.incident().id());

        IncidentDtos.CascadeStep regStep = registered.cascade().get(1);
        IncidentDtos.CascadeStep reStep = reloaded.cascade().get(1);
        assertThat(reStep.kind()).isEqualTo("FOLLOW_UP");
        assertThat(reStep.detail()).isNotEqualTo("재평가 대상 위험요인이 없습니다")
                .isEqualTo(regStep.detail());
        assertThat(reloaded.followUp().regraded()).hasSameSizeAs(registered.followUp().regraded());
        assertThat(reloaded.followUp().regraded()).extracting(FollowUpAssessmentService.Regrade::after)
                .containsExactlyElementsOf(registered.followUp().regraded().stream()
                        .map(FollowUpAssessmentService.Regrade::after).toList());

        assertThat(reloaded.affectedWorkPlans()).extracting(IncidentDtos.AffectedWorkPlan::workPlanId)
                .contains(warned.getId()).doesNotContain(later.getId());
    }
}
