package io.saife.dashboard.service;

import io.saife.dashboard.dto.TimelineDtos;
import io.saife.dashboard.dto.TimelineDtos.Emphasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설비 카드·회상 API 테스트 — Phase 1 (1-1, 1-2).
 *
 * <p>가상 사업장 시드(V2, V7)의 설비 6개를 그대로 쓴다. 시드가 바뀌면 이 테스트의
 * 기대값도 같이 바뀌어야 한다 — 그게 정상이다. <b>불변식만은 시드가 바뀌어도 깨지면 안 된다.</b>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EquipmentTimelineServiceTest {

    /** 시드의 가상 사업장 */
    private static final Long SITE = 1L;

    @Autowired
    private EquipmentTimelineService service;

    @Test
    @DisplayName("불변식 — cards()의 등급·미이행·사고 수는 같은 설비의 timeline().summary()와 항상 같다")
    void cardsMatchTimelineSummaryInvariant() {
        List<TimelineDtos.EquipmentCard> cards = service.cards(SITE);
        assertThat(cards).isNotEmpty();

        for (TimelineDtos.EquipmentCard card : cards) {
            TimelineDtos.TimelineSummary summary = service.timeline(card.id()).summary();

            assertThat(card.currentRiskLevel())
                    .as("설비 %d 등급", card.id())
                    .isEqualTo(summary.currentRiskLevel());
            assertThat(card.currentRiskAxis())
                    .as("설비 %d 축", card.id())
                    .isEqualTo(summary.currentRiskAxis());
            assertThat(card.lastAssessedOn())
                    .as("설비 %d 최근 평가일", card.id())
                    .isEqualTo(summary.lastAssessedOn());
            assertThat(card.unfinishedActionCount())
                    .as("설비 %d 미이행 조치 수", card.id())
                    .isEqualTo(summary.unfinishedActionCount());
            assertThat(card.overdueActionCount())
                    .as("설비 %d 기한 초과 조치 수", card.id())
                    .isEqualTo(summary.overdueActionCount());
            assertThat(card.incidentCount())
                    .as("설비 %d 사고 수", card.id())
                    .isEqualTo(summary.incidentCount());
        }
    }

    @Test
    @DisplayName("정렬 — CRITICAL → WARNING → NORMAL, 같은 등급이면 lastEventOn 내림차순(null은 마지막), 그다음 id")
    void cardsAreSortedByEmphasisThenLastEventOnThenId() {
        List<TimelineDtos.EquipmentCard> cards = service.cards(SITE);

        // 사다리 A(id=1)는 안전대 부착설비 조치가 기한을 넘겨 항상 CRITICAL이다. 맨 앞인지는 보지 않는다:
        // 시드 날짜가 적용 시점 기준이라 오래된 볼륨에서는 고소작업대 조치도 기한을 넘겨 CRITICAL이 둘이 된다
        TimelineDtos.EquipmentCard ladder = cards.stream().filter(c -> c.id() == 1L).findFirst().orElseThrow();
        assertThat(ladder.emphasis()).isEqualTo(Emphasis.CRITICAL);
        assertThat(ladder.overdueActionCount())
                .as("사다리 A는 안전대 부착설비 조치(action id=1)가 기한을 넘겨 있어야 한다")
                .isEqualTo(1);
        assertThat(cards.get(0).emphasis()).isEqualTo(Emphasis.CRITICAL);

        for (int i = 1; i < cards.size(); i++) {
            int prevRank = emphasisRank(cards.get(i - 1).emphasis());
            int curRank = emphasisRank(cards.get(i).emphasis());
            assertThat(curRank)
                    .as("emphasis는 CRITICAL(0) → WARNING(1) → NORMAL(2) 순으로만 내려가야 한다")
                    .isGreaterThanOrEqualTo(prevRank);

            if (curRank == prevRank) {
                var prevAt = cards.get(i - 1).lastEventOn();
                var curAt = cards.get(i).lastEventOn();
                if (prevAt != null && curAt != null) {
                    assertThat(curAt)
                            .as("같은 emphasis 안에서는 lastEventOn 내림차순이어야 한다")
                            .isBeforeOrEqualTo(prevAt);
                } else if (curAt != null) {
                    // prevAt이 null인데 curAt이 값을 가지면 null이 먼저 온 것 — 규칙 위반
                    throw new AssertionError("lastEventOn=null은 항상 마지막이어야 한다: " + cards);
                }
            }
        }

        // 모든 emphasis가 최소 한 번은 등장해 정렬 규칙이 실제로 시험됐는지 확인
        assertThat(cards.stream().map(TimelineDtos.EquipmentCard::emphasis).distinct().toList())
                .as("시드에 CRITICAL·WARNING·NORMAL이 전부 있어야 정렬 규칙이 의미 있게 검증된다")
                .containsExactlyInAnyOrder(Emphasis.CRITICAL, Emphasis.WARNING, Emphasis.NORMAL);
    }

    private int emphasisRank(Emphasis emphasis) {
        return switch (emphasis) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case NORMAL -> 2;
        };
    }

    @Test
    @DisplayName("PRE_WORK 회상 — 위험요인·미이행 조치가 있는 설비는 그 사실을 요약한 headline을 만든다")
    void recallPreWorkHeadlineSummarizesKnownFacts() {
        // 이동식 사다리 A: 최근 평가 '상'(추락) · 미이행 조치(기한 경과) 둘 다 있다
        TimelineDtos.RecallView recall = service.recall(1L);

        assertThat(recall.predicted())
                .as("PRE_WORK는 axis가 없으므로 predicted는 항상 false다")
                .isFalse();
        assertThat(recall.headline()).contains("최근 평가 '상'(추락)");
        assertThat(recall.headline()).contains("미이행 조치 1건(기한 ");
        assertThat(recall.headline()).contains("일 경과)");
        assertThat(recall.headline())
                .as("PRE_WORK 문맥에는 사고를 전제하는 '예고' 표현이 없어야 한다")
                .doesNotContain("예고되어");
    }

    @Test
    @DisplayName("knownSlots — 값이 있는 항목만 담는다")
    void knownSlotsOnlyIncludesPresentValues() {
        // 이동식 사다리 A: 장소·설비·공정·등급·미이행 조치 전부 값이 있다
        TimelineDtos.RecallView ladderRecall = service.recall(1L);
        assertThat(ladderRecall.knownSlots())
                .containsExactlyInAnyOrder("장소", "설비", "공정/작업유형", "최근 평가 등급", "미이행 조치");

        // 유압 프레스 3호(id=4): 위험요인은 있지만 평가·미이행 조치가 없다
        TimelineDtos.RecallView pressRecall = service.recall(4L);
        assertThat(pressRecall.knownSlots())
                .as("값이 없는 '최근 평가 등급'·'미이행 조치'는 담지 않는다")
                .containsExactlyInAnyOrder("장소", "설비", "공정/작업유형")
                .doesNotContain("최근 평가 등급", "미이행 조치");
    }
}
