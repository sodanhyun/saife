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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
 * 새 볼륨에서 시드 당일 기준으로는 12건이다(작업 보류 1, 기한 경과 3, 14일 내 마감 3, 승인 대기 2,
 * 조사표 1, 월 순회점검 1, 정기평가 1).
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

    private List<Long> refIdsOf(TodayDtos.TodayView view, String kind) {
        return itemsOfKind(view, kind).stream().map(TodayDtos.TodayItem::refId).toList();
    }

    private List<TodayDtos.TodayItem> itemsOfKind(TodayDtos.TodayView view, String kind) {
        return view.items().stream().filter(i -> i.kind().equals(kind)).toList();
    }
}
