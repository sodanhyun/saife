package io.saife.dashboard.service;

import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.dashboard.dto.TimelineDtos.Emphasis;
import io.saife.dashboard.dto.TodayDtos;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "오늘 할 일" 7종 규칙 경계값 테스트 — Phase 3 (3-1, 3-3).
 *
 * <p>규칙은 {@code TodayService}의 private 메서드다. 화면 계약은 공개 API
 * {@link TodayService#today()} 하나뿐이라 <b>실제 DB(5433, 시드+V10)에 경계값
 * 데이터를 보태고 {@code today()}를 호출해 결과에서 확인</b>하는 방식으로 검증한다
 * ({@code @Transactional}이라 테스트가 끝나면 자동 롤백 — 시드를 더럽히지 않는다).
 *
 * <p>PATROL_DUE·PERIODIC_DUE는 시드(V2·V10)에 이미 실제 평가 이력이 있어
 * "이번 달 순회점검 없음" 같은 부정 조건을 단순 INSERT만으로 만들 수 없다.
 * 그 두 규칙의 배제 경계는 {@link EntityManager} 네이티브 쿼리로 현재 달의
 * ROUTINE 평가를 걷어내는 방식으로 격리한다 — 실행 날짜에 따라 시드가 우연히
 * "이번 달 순회점검 있음/없음" 어느 쪽이든 될 수 있어, 테스트가 실행 날짜에
 * 좌우되지 않게 하려는 것이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TodayServiceTest {

    private static final Long SITE = 1L;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private TodayService todayService;
    @Autowired
    private ActionRepository actionRepository;
    @Autowired
    private WorkPlanRepository workPlanRepository;
    @Autowired
    private AssessmentRepository assessmentRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private SiteRepository siteRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private LocalDate today() {
        return LocalDate.now(KST);
    }

    // ---------- 규칙 1: OVERDUE_ACTION ----------

    @Test
    @DisplayName("OVERDUE_ACTION — 기한이 어제인 PENDING 조치는 포함된다(CRITICAL)")
    void overdueAction_dueDateYesterday_included() {
        Action action = actionRepository.save(Action.builder()
                .hazardId(1L).content("[TEST] 경계값 — 어제 기한").status(ActionStatus.PENDING)
                .dueDate(today().minusDays(1)).build());

        Optional<TodayDtos.TodayItem> item = findByKindAndRef(todayService.today(), "OVERDUE_ACTION", action.getId());

        assertThat(item).isPresent();
        assertThat(item.get().emphasis()).isEqualTo(Emphasis.CRITICAL);
        assertThat(item.get().daysRemaining()).isEqualTo(-1L);
        assertThat(item.get().equipmentId()).as("hazard 1은 설비 1(이동식 사다리 A)에 속한다").isEqualTo(1L);
    }

    @Test
    @DisplayName("OVERDUE_ACTION — 기한이 오늘(당일)인 PENDING 조치는 아직 포함되지 않는다")
    void overdueAction_dueDateToday_excluded() {
        Action action = actionRepository.save(Action.builder()
                .hazardId(1L).content("[TEST] 경계값 — 오늘 기한").status(ActionStatus.PENDING)
                .dueDate(today()).build());

        Optional<TodayDtos.TodayItem> item = findByKindAndRef(todayService.today(), "OVERDUE_ACTION", action.getId());

        assertThat(item).as("기한 당일은 아직 지난 게 아니다").isEmpty();
    }

    @Test
    @DisplayName("OVERDUE_ACTION — status=DONE이면 기한이 지나도 포함되지 않는다")
    void overdueAction_done_excluded() {
        Action action = actionRepository.save(Action.builder()
                .hazardId(1L).content("[TEST] 경계값 — 완료된 조치").status(ActionStatus.DONE)
                .dueDate(today().minusDays(10)).build());

        Optional<TodayDtos.TodayItem> item = findByKindAndRef(todayService.today(), "OVERDUE_ACTION", action.getId());

        assertThat(item).isEmpty();
    }

    // ---------- 규칙 2: DUE_ACTION ----------

    @Test
    @DisplayName("DUE_ACTION — 기한 당일(D-0)은 포함된다(WARNING)")
    void dueAction_dueDateToday_included() {
        Action action = actionRepository.save(Action.builder()
                .hazardId(1L).content("[TEST] 경계값 — D-0").status(ActionStatus.PENDING)
                .dueDate(today()).build());

        Optional<TodayDtos.TodayItem> item = findByKindAndRef(todayService.today(), "DUE_ACTION", action.getId());

        assertThat(item).isPresent();
        assertThat(item.get().emphasis()).isEqualTo(Emphasis.WARNING);
        assertThat(item.get().daysRemaining()).isEqualTo(0L);
    }

    @Test
    @DisplayName("DUE_ACTION — 14일째(D-14)는 포함되고 15일째는 제외된다")
    void dueAction_day14Included_day15Excluded() {
        Action within = actionRepository.save(Action.builder()
                .hazardId(1L).content("[TEST] 경계값 — D-14").status(ActionStatus.PENDING)
                .dueDate(today().plusDays(14)).build());
        Action beyond = actionRepository.save(Action.builder()
                .hazardId(1L).content("[TEST] 경계값 — D-15").status(ActionStatus.PENDING)
                .dueDate(today().plusDays(15)).build());

        TodayDtos.TodayView view = todayService.today();

        assertThat(findByKindAndRef(view, "DUE_ACTION", within.getId()))
                .as("14일째는 창 안이다").isPresent();
        assertThat(findByKindAndRef(view, "DUE_ACTION", beyond.getId()))
                .as("15일째는 창 밖이다").isEmpty();
    }

    // ---------- 규칙 3: RISKY_WORK_PLAN ----------

    @Test
    @DisplayName("RISKY_WORK_PLAN — 작업일이 7일 이내이고 그 설비에 미이행 조치가 있으면 포함된다(CRITICAL)")
    void riskyWorkPlan_withinWindowAndUnfinishedAction_included() {
        // 설비 1(이동식 사다리 A)은 시드 자체가 기한 초과 미이행 조치를 갖고 있다
        WorkPlan plan = workPlanRepository.save(WorkPlan.builder()
                .siteId(SITE).equipmentId(1L).workName("[TEST] 경계값 — 위험 작업")
                .workDate(today().plusDays(7)).status(WorkPlanStatus.SUBMITTED).build());

        Optional<TodayDtos.TodayItem> item =
                findByKindAndRef(todayService.today(), "RISKY_WORK_PLAN", plan.getId());

        assertThat(item).isPresent();
        assertThat(item.get().emphasis()).isEqualTo(Emphasis.CRITICAL);
        assertThat(item.get().daysRemaining()).isEqualTo(7L);
    }

    @Test
    @DisplayName("RISKY_WORK_PLAN — 작업일이 창(7일) 밖이면 제외된다")
    void riskyWorkPlan_outsideWindow_excluded() {
        WorkPlan plan = workPlanRepository.save(WorkPlan.builder()
                .siteId(SITE).equipmentId(1L).workName("[TEST] 경계값 — 창 밖 작업")
                .workDate(today().plusDays(8)).status(WorkPlanStatus.SUBMITTED).build());

        Optional<TodayDtos.TodayItem> item =
                findByKindAndRef(todayService.today(), "RISKY_WORK_PLAN", plan.getId());

        assertThat(item).isEmpty();
    }

    @Test
    @DisplayName("RISKY_WORK_PLAN — 미이행 조치도 없고 등급도 HIGH가 아닌 설비는 제외된다")
    void riskyWorkPlan_noRiskFactors_excluded() {
        // 설비 4(유압 프레스 3호)는 시드에 평가·조치가 없다(카드-타임라인 테스트로 확인된 사실)
        WorkPlan plan = workPlanRepository.save(WorkPlan.builder()
                .siteId(SITE).equipmentId(4L).workName("[TEST] 경계값 — 무위험 설비 작업")
                .workDate(today().plusDays(1)).status(WorkPlanStatus.APPROVED).build());

        Optional<TodayDtos.TodayItem> item =
                findByKindAndRef(todayService.today(), "RISKY_WORK_PLAN", plan.getId());

        assertThat(item).isEmpty();
    }

    // ---------- 규칙 4: PENDING_APPROVAL ----------

    @Test
    @DisplayName("PENDING_APPROVAL — status=SUBMITTED는 작업일과 무관하게 포함된다(WARNING)")
    void pendingApproval_submitted_included() {
        WorkPlan plan = workPlanRepository.save(WorkPlan.builder()
                .siteId(SITE).equipmentId(2L).workName("[TEST] 경계값 — 승인 대기")
                .workDate(today().plusDays(30)).status(WorkPlanStatus.SUBMITTED).build());

        Optional<TodayDtos.TodayItem> item =
                findByKindAndRef(todayService.today(), "PENDING_APPROVAL", plan.getId());

        assertThat(item).isPresent();
        assertThat(item.get().emphasis()).isEqualTo(Emphasis.WARNING);
    }

    @Test
    @DisplayName("PENDING_APPROVAL — status=DRAFT는 제외된다")
    void pendingApproval_draft_excluded() {
        WorkPlan plan = workPlanRepository.save(WorkPlan.builder()
                .siteId(SITE).equipmentId(2L).workName("[TEST] 경계값 — 초안")
                .workDate(today().plusDays(1)).status(WorkPlanStatus.DRAFT).build());

        Optional<TodayDtos.TodayItem> item =
                findByKindAndRef(todayService.today(), "PENDING_APPROVAL", plan.getId());

        assertThat(item).isEmpty();
    }

    // ---------- 규칙 5: REPORT_DUE ----------

    @Test
    @DisplayName("REPORT_DUE — 기한이 3일 이내면 CRITICAL, 4일이면 WARNING")
    void reportDue_threeDaysCritical_fourDaysWarning() {
        Incident critical = incidentRepository.save(Incident.builder()
                .siteId(SITE).equipmentId(1L)
                .occurredAt(OffsetDateTime.now().minusDays(27))
                .accidentType(AccidentType.FALL).leaveDays(5).severity(IncidentSeverity.LOST_TIME)
                .reportDueDate(today().plusDays(3)).reportStatus(ReportStatus.REQUIRED).build());
        Incident warning = incidentRepository.save(Incident.builder()
                .siteId(SITE).equipmentId(1L)
                .occurredAt(OffsetDateTime.now().minusDays(26))
                .accidentType(AccidentType.FALL).leaveDays(5).severity(IncidentSeverity.LOST_TIME)
                .reportDueDate(today().plusDays(4)).reportStatus(ReportStatus.REQUIRED).build());

        TodayDtos.TodayView view = todayService.today();

        assertThat(findByKindAndRef(view, "REPORT_DUE", critical.getId()))
                .get().extracting(TodayDtos.TodayItem::emphasis).isEqualTo(Emphasis.CRITICAL);
        assertThat(findByKindAndRef(view, "REPORT_DUE", warning.getId()))
                .get().extracting(TodayDtos.TodayItem::emphasis).isEqualTo(Emphasis.WARNING);
    }

    @Test
    @DisplayName("REPORT_DUE — report_status=SUBMITTED는 제외된다 (V10 이야기의 사고가 그 예다)")
    void reportDue_submitted_excluded() {
        // V10 시드의 incident id=1(고소작업대)이 이미 SUBMITTED다 — 오늘 할 일에 뜨면 안 된다.
        TodayDtos.TodayView view = todayService.today();
        assertThat(findByKindAndRef(view, "REPORT_DUE", 1L)).isEmpty();
    }

    // ---------- 규칙 6: PATROL_DUE ----------

    @Test
    @DisplayName("PATROL_DUE — site.regular_track=false면 제외된다")
    void patrolDue_regularTrackFalse_excluded() {
        entityManager.createNativeQuery("UPDATE site SET regular_track = false WHERE id = :id")
                .setParameter("id", SITE).executeUpdate();
        entityManager.flush();
        entityManager.clear();

        List<TodayDtos.TodayItem> patrol = itemsOfKind(todayService.today(), "PATROL_DUE");

        assertThat(patrol).isEmpty();
    }

    @Test
    @DisplayName("PATROL_DUE — regular_track=true이고 이번 달 ROUTINE 평가가 없으면 포함된다(WARNING)")
    void patrolDue_noRoutineThisMonth_included() {
        // 이번 달의 ROUTINE 평가를 전부 걷어낸다 — 실행 날짜에 따라 V2·V10 시드가
        // 우연히 이번 달에 순회점검을 갖고 있을 수도, 없을 수도 있어서 결정적으로 만든다.
        entityManager.createNativeQuery(
                "DELETE FROM assessment_hazard WHERE assessment_id IN "
                        + "(SELECT id FROM assessment WHERE kind = 'ROUTINE' "
                        + "AND date_trunc('month', assessed_on) = date_trunc('month', CURRENT_DATE))")
                .executeUpdate();
        entityManager.createNativeQuery(
                "DELETE FROM assessment WHERE kind = 'ROUTINE' "
                        + "AND date_trunc('month', assessed_on) = date_trunc('month', CURRENT_DATE)")
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        List<TodayDtos.TodayItem> patrol = itemsOfKind(todayService.today(), "PATROL_DUE");

        assertThat(patrol).hasSize(1);
        assertThat(patrol.get(0).emphasis()).isEqualTo(Emphasis.WARNING);
        assertThat(patrol.get(0).linkType()).isEqualTo("ASSESSMENT");
    }

    // ---------- 규칙 7: PERIODIC_DUE ----------

    @Test
    @DisplayName("PERIODIC_DUE — 최근 INITIAL/REGULAR 평가 + 1년이 오늘+60일 이내면 포함된다")
    void periodicDue_within60Days_included() {
        // assessed_on을 오늘-1년+60일로 잡으면 만료일이 정확히 오늘+60일 — 경계 포함 확인.
        // 시드(assessment id=3, INITIAL)보다 더 최근 날짜라 "가장 최근"으로 뽑힌다.
        assessmentRepository.save(Assessment.builder()
                .siteId(SITE).kind(AssessmentKind.INITIAL)
                .assessedOn(today().minusYears(1).plusDays(60))
                .status("CONFIRMED").build());

        List<TodayDtos.TodayItem> periodic = itemsOfKind(todayService.today(), "PERIODIC_DUE");

        assertThat(periodic).hasSize(1);
        assertThat(periodic.get(0).daysRemaining()).isEqualTo(60L);
    }

    @Test
    @DisplayName("PERIODIC_DUE — 만료가 오늘+61일이면 제외된다")
    void periodicDue_beyond60Days_excluded() {
        assessmentRepository.save(Assessment.builder()
                .siteId(SITE).kind(AssessmentKind.REGULAR)
                .assessedOn(today().minusYears(1).plusDays(61))
                .status("CONFIRMED").build());

        List<TodayDtos.TodayItem> periodic = itemsOfKind(todayService.today(), "PERIODIC_DUE");

        assertThat(periodic).isEmpty();
    }

    // ---------- 정렬·상한 ----------

    @Test
    @DisplayName("정렬·상한 — CRITICAL 항목이 20건을 넘으면 20건으로 잘리고, daysRemaining 오름차순을 유지한다")
    void sortAndCap_limitsToTwentyInDaysRemainingOrder() {
        for (int i = 1; i <= 25; i++) {
            actionRepository.save(Action.builder()
                    .hazardId(1L).content("[TEST] 상한 확인 #" + i).status(ActionStatus.PENDING)
                    .dueDate(today().minusDays(i)).build());
        }

        TodayDtos.TodayView view = todayService.today();

        assertThat(view.items()).hasSize(20);
        assertThat(view.criticalCount())
                .as("criticalCount는 캡 이후 items와 항상 일치해야 한다")
                .isEqualTo((int) view.items().stream().filter(i -> i.emphasis() == Emphasis.CRITICAL).count());
        for (int i = 1; i < view.items().size(); i++) {
            Long prev = view.items().get(i - 1).daysRemaining();
            Long cur = view.items().get(i).daysRemaining();
            if (prev != null && cur != null) {
                assertThat(cur).as("daysRemaining 오름차순").isGreaterThanOrEqualTo(prev);
            }
        }
    }

    // ---------- 보조 ----------

    private Optional<TodayDtos.TodayItem> findByKindAndRef(TodayDtos.TodayView view, String kind, Long refId) {
        return view.items().stream()
                .filter(i -> i.kind().equals(kind) && refId.equals(i.refId()))
                .findFirst();
    }

    private List<TodayDtos.TodayItem> itemsOfKind(TodayDtos.TodayView view, String kind) {
        return view.items().stream().filter(i -> i.kind().equals(kind)).toList();
    }
}
