package io.saife.dashboard.service;

import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.Assessment;
import io.saife.core.domain.AssessmentKind;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentRepository;
import io.saife.dashboard.dto.TodayDtos;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.repository.WorkPlanWorkerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "오늘 할 일" 시드 스냅샷 (V14, V15 데모 규모 시드 기준).
 *
 * <p><b>왜 고정된 개수로 박지 않는가.</b> 시드 날짜는 마이그레이션이 적용된 날 기준 상대값이다.
 * 테스트 DB는 며칠 전에 마이그레이션됐을 수 있고, 그 사이 기한 7일 남은 조치가 기한 경과로 넘어간다.
 * 그래서 숫자 대신 <b>시드 행 자체와 KST "오늘"에서 매번 다시 계산한 불변식</b>으로 검증한다.
 * 녹화 기준일(2026-10-07 수, 그날 새 볼륨) 기준으로는 12건이다(작업 보류 1, 기한 경과 3, 14일 내 마감 3,
 * 승인 대기 2, 조사표 1, 월 순회점검 1, 정기평가 1). 주말에 만든 볼륨은 다음 평일 작업과 기한이 밀려 11~12건이다.
 *
 * <p>시드 날짜는 평일로 보정된다(V14/V15의 pg_temp.saife_wd). 그래서 이 클래스는 "시드 날짜가 주말이 아니다",
 * "지난 작업일은 완료, 승인 뒤 TBM", "사고 시점 미이행 조치는 사고 전에 생기고 기한이 지나 있다" 같은
 * 서사 불변식도 본다.
 *
 * <p>규칙 자체의 경계값은 {@link TodayServiceTest}가 자체 주입 데이터로 검증한다. 이 클래스는
 * "시드가 실제로 그 불변식을 만족하는 상태로 있는가"만 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TodaySeedSnapshotTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** V15: 천장크레인 사고(물체에 맞음, 휴업 14일). 조사표 작성 필요 */
    private static final Long CRANE_INCIDENT = 6L;
    /** V15: 사고 6 뒤 작업 보류된 천장크레인 인양 */
    private static final Long HELD_WORK_PLAN = 42L;

    @Autowired
    private TodayService todayService;
    @Autowired
    private ActionRepository actionRepository;
    @Autowired
    private AssessmentRepository assessmentRepository;
    @Autowired
    private WorkPlanRepository workPlanRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private WorkPlanWorkerRepository workPlanWorkerRepository;

    @Test
    @DisplayName("시드 불변식: 기한 경과, 14일 내 마감, 승인 대기, 작업 보류, 조사표, 순회점검, 정기평가가 시드 행에서 그대로 따라 나온다")
    void todayReflectsSeedRowsAsInvariants() {
        LocalDate today = LocalDate.now(KST);
        TodayDtos.TodayView view = todayService.today();

        // ── 시연 설비(사다리 A)의 기한 경과 조치 1은 날짜와 무관하게 항상 뜬다 ──
        assertThat(refIdsOf(view, "OVERDUE_ACTION")).contains(1L);

        // ── 미이행 조치마다: 기한이 지났으면 기한 경과, 14일 안이면 마감 임박 ──
        for (Action action : actionRepository.findAll()) {
            if (action.getId() > 54 || action.getStatus() == ActionStatus.DONE || action.getDueDate() == null) {
                continue;
            }
            boolean overdue = action.getStatus() == ActionStatus.OVERDUE || action.getDueDate().isBefore(today);
            if (overdue) {
                assertThat(refIdsOf(view, "OVERDUE_ACTION"))
                        .as("조치 %d 기한 %s, 오늘 %s", action.getId(), action.getDueDate(), today)
                        .contains(action.getId());
            } else if (!action.getDueDate().isAfter(today.plusDays(14))) {
                assertThat(refIdsOf(view, "DUE_ACTION"))
                        .as("조치 %d 기한 %s는 14일 창 안", action.getId(), action.getDueDate())
                        .contains(action.getId());
            } else {
                assertThat(refIdsOf(view, "DUE_ACTION")).doesNotContain(action.getId());
            }
        }
        // 이행 완료 조치는 어디에도 뜨지 않는다
        List<Long> doneIds = actionRepository.findAll().stream()
                .filter(a -> a.getStatus() == ActionStatus.DONE).map(Action::getId).toList();
        assertThat(refIdsOf(view, "OVERDUE_ACTION")).doesNotContainAnyElementsOf(doneIds);
        assertThat(refIdsOf(view, "DUE_ACTION")).doesNotContainAnyElementsOf(doneIds);

        // ── 승인 대기: SUBMITTED 작업 전 점검 전부 ──
        List<Long> submitted = workPlanRepository.findAll().stream()
                .filter(p -> p.getStatus() == WorkPlanStatus.SUBMITTED).map(WorkPlan::getId).toList();
        assertThat(submitted).as("V15는 오늘, 내일 작업 2건을 승인 대기로 둔다").contains(38L, 39L);
        assertThat(refIdsOf(view, "PENDING_APPROVAL")).containsAll(submitted);

        // ── 작업 보류: 사고 6 뒤 보류된 천장크레인 인양 ──
        assertThat(workPlanRepository.findById(HELD_WORK_PLAN).orElseThrow().getStatus()).isEqualTo(WorkPlanStatus.HOLD);
        assertThat(refIdsOf(view, "WORK_HOLD")).contains(HELD_WORK_PLAN);
        assertThat(view.items().get(0).kind()).as("작업 보류는 긴급 중에서도 맨 위").isEqualTo("WORK_HOLD");

        // ── 조사표: 제출 전 사고만 뜬다. V10 사고 1과 V15 사고 5는 제출 완료라 뜨지 않는다 ──
        Incident crane = incidentRepository.findById(CRANE_INCIDENT).orElseThrow();
        assertThat(crane.getReportStatus()).isIn(ReportStatus.REQUIRED, ReportStatus.OVERDUE);
        assertThat(refIdsOf(view, "REPORT_DUE")).contains(CRANE_INCIDENT).doesNotContain(1L, 5L);

        // ── 정기평가: 가장 최근 최초/정기 평가가 올해가 아니면 연말 기한으로 1건 ──
        Assessment latestPeriodic = assessmentRepository.findBySiteIdOrderByAssessedOnDesc(1L).stream()
                .filter(a -> a.getKind() == AssessmentKind.INITIAL || a.getKind() == AssessmentKind.REGULAR)
                .max(Comparator.comparing(Assessment::getAssessedOn)).orElseThrow();
        List<TodayDtos.TodayItem> periodicItems = itemsOfKind(view, "PERIODIC_DUE");
        if (latestPeriodic.getAssessedOn().getYear() < today.getYear()) {
            assertThat(periodicItems).hasSize(1);
            assertThat(periodicItems.get(0).refId()).isEqualTo(latestPeriodic.getId());
            assertThat(periodicItems.get(0).dueDate()).isEqualTo(LocalDate.of(today.getYear(), 12, 31));
        } else {
            assertThat(periodicItems).isEmpty();
        }

        // ── 월 순회점검: 이번 달 ROUTINE 평가가 없으면 1건 ──
        YearMonth thisMonth = YearMonth.from(today);
        boolean routineThisMonth = assessmentRepository.findBySiteIdOrderByAssessedOnDesc(1L).stream()
                .anyMatch(a -> a.getKind() == AssessmentKind.ROUTINE
                        && YearMonth.from(a.getAssessedOn()).equals(thisMonth));
        assertThat(itemsOfKind(view, "PATROL_DUE")).hasSize(routineThisMonth ? 0 : 1);

        // ── 시연 설비에는 오늘 이후 작업 전 점검이 없다(녹화에서 새로 만든다) ──
        assertThat(workPlanRepository.findAll().stream()
                .filter(p -> p.getId() <= 42 && Long.valueOf(1L).equals(p.getEquipmentId()))).isEmpty();
        assertThat(refIdsOf(view, "RISKY_WORK_PLAN")).as("시드의 다가오는 작업은 미이행 조치 없는 설비에만 있다")
                .allMatch(id -> id > 42);

        // 화면 문자열 규칙: 대시, 가운뎃점, 따옴표 등급을 쓰지 않는다
        view.items().forEach(i -> assertThat(i.title() + " " + i.detail())
                .as("오늘 할 일 문자열").doesNotContain("—", "–", "·", "'상'", "상시평가 트랙"));
    }

    @Test
    @DisplayName("시드 서사: 날짜는 평일, 지난 작업일은 완료, 승인 뒤 TBM, 승인자는 그 작업의 관리감독자, 크레인 사고 시점 미이행 조치")
    void seedStoryInvariants() {
        LocalDate today = LocalDate.now(KST);

        // 날짜는 평일(주말에 평가, 작업, 사고, 기한을 두지 않는다)
        assessmentRepository.findBySiteIdOrderByAssessedOnDesc(1L).stream().filter(a -> a.getId() <= 24)
                .forEach(a -> assertWeekday("평가 " + a.getId(), a.getAssessedOn()));
        actionRepository.findAll().stream().filter(a -> a.getId() <= 54).forEach(a -> {
            assertWeekday("조치 기한 " + a.getId(), a.getDueDate());
            assertWeekday("조치 생성 " + a.getId(), kst(a.getCreatedAt()));
            if (a.getCompletedAt() != null) {
                assertWeekday("조치 완료 " + a.getId(), kst(a.getCompletedAt()));
                assertThat(a.getCompletedAt()).as("조치 %d 완료는 생성 뒤", a.getId()).isAfter(a.getCreatedAt());
            }
        });
        incidentRepository.findAll().stream().filter(i -> i.getId() <= 6)
                .forEach(i -> assertWeekday("사고 " + i.getId(), kst(i.getOccurredAt())));

        for (WorkPlan p : workPlanRepository.findAll()) {
            if (p.getId() > 42) {
                continue;
            }
            assertWeekday("작업 " + p.getId(), p.getWorkDate());
            if (p.getWorkDate().isBefore(today)) {
                assertThat(p.getStatus()).as("지난 작업일 작업 %d는 완료", p.getId()).isEqualTo(WorkPlanStatus.CLOSED);
            }
            if (p.getApprovedAt() != null) {
                assertThat(p.getApprovedAt()).as("작업 %d 승인은 작성 뒤", p.getId()).isAfter(p.getCreatedAt());
                List<String> supervisors = workPlanWorkerRepository.findByWorkPlanId(p.getId()).stream()
                        .filter(w -> "관리감독자".equals(w.getDuty()) || "작업지휘자".equals(w.getDuty()))
                        .map(w -> w.getName()).toList();
                assertThat(supervisors).as("작업 %d 승인자는 그 작업의 관리감독자", p.getId()).contains(p.getApprovedBy());
            }
            if (p.getBriefingAckAt() != null) {
                assertThat(p.getApprovedAt()).as("작업 %d TBM은 승인 뒤", p.getId()).isNotNull();
                assertThat(p.getBriefingAckAt()).isAfter(p.getApprovedAt());
                assertThat(kst(p.getBriefingAckAt())).as("작업 %d TBM은 작업 당일", p.getId()).isEqualTo(p.getWorkDate());
            }
            assertThat(nvl(p.getApprovalNote())).as("승인 조건에 접두어를 쓰지 않는다").doesNotStartWith("잠정조치:");
        }

        // 크레인 사고 6: 훅 해지장치 교체(조치 44)는 사고 전에 지적됐고, 사고 때 기한이 지나 있었고, 사고 뒤에 이행됐다
        Incident crane = incidentRepository.findById(CRANE_INCIDENT).orElseThrow();
        Action hookLatch = actionRepository.findById(44L).orElseThrow();
        assertThat(hookLatch.getCreatedAt()).isBefore(crane.getOccurredAt());
        assertThat(hookLatch.getDueDate()).isBefore(kst(crane.getOccurredAt()));
        assertThat(hookLatch.getCompletedAt()).isAfter(crane.getOccurredAt());
        assertThat(crane.getLeaveDays()).isEqualTo(42);
        assertThat(crane.getPrevention()).as("재발방지는 기한을 날짜로 쓴다")
                .doesNotContain("작업 재개 전").containsPattern("기한 \\d{4}-\\d{2}-\\d{2}\\)");

        // 고소작업대 사고 1(V10)은 시연 사고(이동식 사다리 A, 10:20, 휴업 5일)와 시각, 휴업일수가 겹치지 않는다
        Incident aerial = incidentRepository.findById(1L).orElseThrow();
        assertThat(aerial.getLeaveDays()).isNotEqualTo(5);
        assertThat(aerial.getOccurredAt().atZoneSameInstant(KST).toLocalTime().toString()).isNotEqualTo("10:20");

        // 이동식 사다리 A: 근로자 답변과 충돌하는 "2인 1조, 넘어짐 방지" 같은 이행 완료 조치가 없다
        assertThat(actionRepository.findAll().stream()
                .filter(a -> a.getHazardId() == 1L || a.getHazardId() == 2L)
                .map(Action::getContent)).noneMatch(c -> c.contains("넘어짐 방지") || c.contains("2인 1조"));
    }

    private static void assertWeekday(String what, LocalDate d) {
        assertThat(d.getDayOfWeek()).as("%s %s는 평일", what, d).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    private static LocalDate kst(java.time.OffsetDateTime t) {
        return t.atZoneSameInstant(KST).toLocalDate();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private List<Long> refIdsOf(TodayDtos.TodayView view, String kind) {
        return itemsOfKind(view, kind).stream().map(TodayDtos.TodayItem::refId).toList();
    }

    private List<TodayDtos.TodayItem> itemsOfKind(TodayDtos.TodayView view, String kind) {
        return view.items().stream().filter(i -> i.kind().equals(kind)).toList();
    }
}
