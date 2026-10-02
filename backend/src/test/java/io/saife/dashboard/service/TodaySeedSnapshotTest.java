package io.saife.dashboard.service;

import io.saife.core.domain.Action;
import io.saife.core.domain.Assessment;
import io.saife.core.domain.AssessmentKind;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentRepository;
import io.saife.dashboard.dto.TodayDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "오늘 할 일" 시드 스냅샷 — Phase 3 §3-3 (R55 수정판).
 *
 * <p><b>왜 고정된 개수로 박지 않는가.</b> 설계서({@code docs/SAIFE_연결성_개선_프롬프트.md}
 * "## Phase 4")의 기대표는 V2 시드가 최초 적용된 날(2026-09-21)을 "오늘"로 가정해
 * 계산됐다. 그런데 이 시드 시스템은 <b>마이그레이션마다 다른 실제 적용일에 상대
 * 날짜가 고정된다</b> — V2(2026-09-21)와 V10(2026-09-29)이 8일 차이가 난다. 그 결과
 * 예를 들어 action 5(고소작업대, V2의 "+7일")의 기한은 시드 당시엔 미래였지만
 * <b>오늘이 하루씩 지날 때마다 그 기한과의 거리도 매일 줄어든다</b> — action 5가
 * OVERDUE_ACTION으로 넘어가는 날짜, PERIODIC_DUE의 D-n 값 전부 실행 날짜에 따라
 * 계속 바뀐다. 이 값을 텍스트로 못박으면 테스트가 내일부터 깨진다(2026-09-29
 * 최초 작성 시 이미 겪음 — OVERDUE_ACTION 1건 기대가 실측 2건으로 나왔다).
 *
 * <p>그래서 <b>시드 행 자체와 KST "오늘"에서 매번 다시 계산한 불변식</b>으로
 * 검증한다 — "숫자가 얼마인가"가 아니라 "그 항목이 그 조건을 만족하면 반드시
 * 보이고, 안 만족하면 반드시 안 보인다"를 확인한다. 이러면 실행 날짜가 몇 일이든
 * 테스트가 깨지지 않는다.
 *
 * <p>PATROL_DUE·PERIODIC_DUE·REPORT_DUE 등 규칙 조건 자체의 경계값(기한 당일,
 * 14일째, ≤3일 CRITICAL 등)은 {@link TodayServiceTest}가 자체 주입 데이터로
 * 이미 검증한다 — 이 클래스는 "시드가 실제로 그 불변식을 만족하는 상태로
 * 있는가"만 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TodaySeedSnapshotTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private TodayService todayService;
    @Autowired
    private ActionRepository actionRepository;
    @Autowired
    private AssessmentRepository assessmentRepository;

    @Test
    @DisplayName("시드 불변식 — action 1/4/5, assessment 3/ROUTINE 이번 달 여부에서 today() 결과가 그대로 따라 나온다")
    void todayReflectsSeedRowsAsInvariants() {
        LocalDate today = LocalDate.now(KST);
        TodayDtos.TodayView view = todayService.today();

        // ── action 1(사다리 A) — 시드 자체가 기한 초과·PENDING이라 날짜와 무관하게 항상 OVERDUE_ACTION ──
        assertThat(refIdsOf(view, "OVERDUE_ACTION"))
                .as("action 1(사다리 A, 안전대 부착설비 미설치)은 시드가 이미 기한을 넘긴 채 PENDING이라 "
                        + "실행 날짜와 무관하게 항상 떠야 한다")
                .contains(1L);

        // ── action 5(고소작업대) — 기한이 이미 지났는지 여부에 따라 OVERDUE_ACTION/DUE_ACTION이 갈린다 ──
        Action action5 = actionRepository.findById(5L).orElseThrow();
        boolean action5Overdue = action5.getDueDate().isBefore(today);
        if (action5Overdue) {
            assertThat(refIdsOf(view, "OVERDUE_ACTION"))
                    .as("action 5 기한(%s)이 오늘(%s)보다 이전이면 OVERDUE_ACTION에 있어야 한다",
                            action5.getDueDate(), today)
                    .contains(5L);
        } else {
            assertThat(refIdsOf(view, "OVERDUE_ACTION"))
                    .as("action 5 기한(%s)이 아직 안 지났으면 OVERDUE_ACTION에 없어야 한다",
                            action5.getDueDate())
                    .doesNotContain(5L);
        }

        // ── action 4(지게차) — DUE_ACTION 창(오늘~오늘+14일) 안에 있는지로 갈린다 ──
        Action action4 = actionRepository.findById(4L).orElseThrow();
        boolean action4InDueWindow = !action4.getDueDate().isBefore(today)
                && !action4.getDueDate().isAfter(today.plusDays(14));
        if (action4InDueWindow) {
            assertThat(refIdsOf(view, "DUE_ACTION"))
                    .as("action 4 기한(%s)이 오늘(%s)~+14일 창 안이면 DUE_ACTION에 있어야 한다",
                            action4.getDueDate(), today)
                    .contains(4L);
        } else {
            assertThat(refIdsOf(view, "DUE_ACTION"))
                    .as("action 4 기한(%s)이 창 밖이면 DUE_ACTION에 없어야 한다", action4.getDueDate())
                    .doesNotContain(4L);
        }

        // ── assessment 3(INITIAL, 2025-11-21) — PERIODIC_DUE의 유일한 후보. V10은 ROUTINE만
        //    추가했으므로(INITIAL/REGULAR 아님) "가장 최근 INITIAL/REGULAR"는 항상 assessment 3다 ──
        Assessment assessment3 = assessmentRepository.findById(3L).orElseThrow();
        boolean notThisYear = assessment3.getAssessedOn().getYear() < today.getYear();
        List<TodayDtos.TodayItem> periodicItems = itemsOfKind(view, "PERIODIC_DUE");
        if (notThisYear) {
            assertThat(periodicItems).as("assessment 3(%s)가 올해 평가가 아니면 '올해 정기평가 미실시'가 1건이어야 한다",
                    assessment3.getAssessedOn()).hasSize(1);
            assertThat(periodicItems.get(0).refId()).isEqualTo(3L);
            assertThat(periodicItems.get(0).dueDate()).isEqualTo(LocalDate.of(today.getYear(), 12, 31));
        } else {
            assertThat(periodicItems).as("assessment 3(%s)가 올해 평가면 PERIODIC_DUE는 없어야 한다",
                    assessment3.getAssessedOn()).isEmpty();
        }

        // ── PATROL_DUE — "이번 달 ROUTINE 평가가 있는가"를 시드에서 직접 다시 계산해 대조한다 ──
        YearMonth thisMonth = YearMonth.from(today);
        boolean routineThisMonthExists = assessmentRepository.findBySiteIdOrderByAssessedOnDesc(1L).stream()
                .anyMatch(a -> a.getKind() == AssessmentKind.ROUTINE
                        && YearMonth.from(a.getAssessedOn()).equals(thisMonth));
        List<TodayDtos.TodayItem> patrolItems = itemsOfKind(view, "PATROL_DUE");
        if (routineThisMonthExists) {
            assertThat(patrolItems)
                    .as("이번 달(%s)에 이미 ROUTINE 평가가 있으면 PATROL_DUE는 없어야 한다", thisMonth)
                    .isEmpty();
        } else {
            assertThat(patrolItems)
                    .as("이번 달(%s)에 ROUTINE 평가가 없으면 PATROL_DUE가 1건 있어야 한다"
                            + "(site.regular_track=true는 V2 시드에 고정돼 있다)", thisMonth)
                    .hasSize(1);
        }

        // ── V10 이야기가 고정한 사실들 — 날짜와 무관하게 항상 성립해야 한다 ──
        assertThat(itemsOfKind(view, "REPORT_DUE"))
                .as("V10의 사고(incident 1)는 report_status=SUBMITTED다 — 제출 완료된 조사표는 절대 뜨면 안 된다")
                .isEmpty();
        // 화면 문자열 규칙: 대시, 가운뎃점, 따옴표 등급을 쓰지 않는다
        view.items().forEach(i -> assertThat(i.title() + " " + i.detail())
                .as("오늘 할 일 문자열").doesNotContain("\u2014", "\u2013", "\u00B7", "'상'", "상시평가 트랙"));
        assertThat(itemsOfKind(view, "RISKY_WORK_PLAN"))
                .as("V10의 work_plan 1은 작업일이 -50일이라 오늘로부터 7일 창을 이미 한참 벗어났다")
                .isEmpty();
    }

    private List<Long> refIdsOf(TodayDtos.TodayView view, String kind) {
        return itemsOfKind(view, kind).stream().map(TodayDtos.TodayItem::refId).toList();
    }

    private List<TodayDtos.TodayItem> itemsOfKind(TodayDtos.TodayView view, String kind) {
        Predicate<TodayDtos.TodayItem> matches = i -> i.kind().equals(kind);
        return view.items().stream().filter(matches).toList();
    }
}
