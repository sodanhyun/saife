package io.saife.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.action.ActionSuggestionTable;
import io.saife.core.domain.AccidentType;
import io.saife.core.service.RiskRuleEngine;
import io.saife.core.service.RiskRuleEngine.SlotKeys;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import io.saife.evidence.service.LawCitationTable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 설비 종류별 근거 조문, 필수 확인 항목, 점검표에 남길 근거(무관 조문 제외), 기준표 권고 대책.
 */
class PlanRulesTest {

    private static List<Integer> articles(List<LawCitationTable.Citation> cs) {
        return cs.stream().map(LawCitationTable.Citation::articleNo).toList();
    }

    @Test
    void 설비별로_판정과_같은_조문을_붙인다() {
        assertThat(articles(HazardAnalysisTools.citationsFor(AccidentType.FALL, RiskRuleEngine.KIND_LADDER, false)))
                .containsExactly(42).doesNotContain(44);
        assertThat(articles(HazardAnalysisTools.citationsFor(AccidentType.FALL, RiskRuleEngine.KIND_MOBILE_SCAFFOLD, false)))
                .containsExactly(68);
        assertThat(articles(HazardAnalysisTools.citationsFor(AccidentType.FALL, RiskRuleEngine.KIND_ROOF, false)))
                .containsExactly(45, 44);
        assertThat(articles(HazardAnalysisTools.citationsFor(AccidentType.DROP, RiskRuleEngine.KIND_CRANE, false)))
                .containsExactly(146, 137, 163);
        assertThat(articles(HazardAnalysisTools.citationsFor(AccidentType.CAUGHT, RiskRuleEngine.KIND_PRESS, false)))
                .containsExactly(103, 104);
        List<LawCitationTable.Citation> fire = HazardAnalysisTools.citationsFor(AccidentType.FIRE, RiskRuleEngine.KIND_WELDER, true);
        assertThat(fire).extracting(LawCitationTable.Citation::articleNo, LawCitationTable.Citation::articleSub)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(241, 0), org.assertj.core.groups.Tuple.tuple(241, 2));
    }

    @Test
    void 용접과_크레인_작업은_축을_빠뜨리지_않는다() {
        assertThat(HazardAnalysisTools.deriveAxes("CO2 용접기 브래킷 용단")).contains(AccidentType.FIRE);
        assertThat(HazardAnalysisTools.deriveAxes("천장 호이스트 금형 인양")).contains(AccidentType.DROP);
    }

    @Test
    void 이동식_비계는_높이_안전난간_바퀴_고정을_묻고_크레인은_높이를_묻지_않는다() {
        assertThat(WorkPlanTools.requiredSlotsFor("배관 보온", RiskRuleEngine.KIND_MOBILE_SCAFFOLD))
                .containsExactly(SlotKeys.WORK_HEIGHT, SlotKeys.PLATFORM_GUARDRAIL, SlotKeys.CASTER_LOCK);
        assertThat(WorkPlanTools.requiredSlotsFor("금형 인양 (1.2톤)", RiskRuleEngine.KIND_CRANE)).isEmpty();
        assertThat(WorkPlanTools.requiredSlotsFor("금형 교체", RiskRuleEngine.KIND_PRESS)).containsExactly(SlotKeys.GUARD_INSTALLED);
        assertThat(WorkPlanTools.requiredSlotsFor("천장 페인트 작업", RiskRuleEngine.KIND_LADDER))
                .containsExactly(SlotKeys.WORK_HEIGHT, SlotKeys.TOP_STEP, SlotKeys.TIP_GUARD, SlotKeys.PRODUCT_NAME);
    }

    private static Evidence law(int articleNo) {
        return new Evidence(1, EvidenceKind.LAW, 1L, "L:" + articleNo + ":0", "제" + articleNo + "조", "", null, null, null,
                Origin.CACHE, 1.0, OffsetDateTime.now(),
                Map.of("lawName", LawCitationTable.RULES, "articleNo", articleNo, "articleSub", 0));
    }

    @Test
    void 사다리_점검표_근거에서_안전대_부착설비_조문은_뺀다() {
        assertThat(WorkPlanTools.relevantToPlan(law(42), RiskRuleEngine.KIND_LADDER)).isTrue();
        assertThat(WorkPlanTools.relevantToPlan(law(44), RiskRuleEngine.KIND_LADDER)).isFalse();
        assertThat(WorkPlanTools.relevantToPlan(law(32), RiskRuleEngine.KIND_LADDER)).isTrue();
        assertThat(WorkPlanTools.relevantToPlan(law(44), null)).isTrue();
    }

    @Test
    void 기준표는_모든_발생형태에_권고_대책을_낸다() {
        for (AccidentType axis : AccidentType.values()) {
            assertThat(ActionSuggestionTable.suggest(axis, "", null)).as(axis.name()).isNotNull();
        }
        assertThat(ActionSuggestionTable.suggest(AccidentType.DROP, "훅 해지장치 파손", null).lawRef()).endsWith("제137조");
        assertThat(ActionSuggestionTable.suggest(AccidentType.CAUGHT, "프레스 광전자식 방호장치 해제", null).lawRef()).endsWith("제103조");
        assertThat(ActionSuggestionTable.suggest(AccidentType.FALL, "지붕 채광창 덮개 없음", null).lawRef()).endsWith("제45조");
        assertThat(ActionSuggestionTable.suggest(AccidentType.PPE, "방독마스크 미착용", null).lawRef()).endsWith("제450조");
    }
}
