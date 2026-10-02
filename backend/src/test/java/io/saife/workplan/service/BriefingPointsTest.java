package io.saife.workplan.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import io.saife.core.service.RiskRuleEngine;
import io.saife.core.service.RiskRuleEngine.SlotKeys;
import io.saife.workplan.domain.WorkDocument;
import io.saife.workplan.dto.WorkPlanDtos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TBM 위험 포인트와 지킬 것: 설비 종류별 규칙, 항상 2~3개. 시드에 저장된 TBM 원문과 같은 말이 나와야
 * 화면(구조화 뷰)과 저장 원문이 어긋나지 않는다.
 */
class BriefingPointsTest {

    private final RiskRuleEngine engine = new RiskRuleEngine();

    private List<WorkPlanDtos.HazardDecision> decide(Map<String, String> slots, AccidentType... axes) {
        List<WorkPlanDtos.HazardDecision> out = new ArrayList<>();
        for (AccidentType axis : axes) {
            RiskRuleEngine.Decision d = engine.decide(axis, slots);
            out.add(new WorkPlanDtos.HazardDecision(axis, axis.getLabel(), d.riskLevel(), d.frequency(), d.severity(),
                    d.ruleTrace(), engine.recommendation(axis, slots, d)));
        }
        out.sort(Comparator.comparingInt(x -> x.riskLevel().ordinal()));
        return out;
    }

    @Test
    void 크레인_인양은_시드_TBM과_같은_세_줄이다() {
        Map<String, String> slots = RiskRuleEngine.withEquipmentKind(Map.of(), "천장크레인 1호", "금형 인양 (1.2톤)");
        List<WorkPlanDtos.HazardDecision> d = decide(slots, AccidentType.DROP, AccidentType.PPE);

        assertThat(BriefingViewBuilder.riskPoints(d, slots)).containsExactly(
                "인양물이 떨어지거나 흔들려 사람에 맞을 수 있습니다",
                "훅 해지장치나 슬링이 손상되면 줄걸이가 빠질 수 있습니다",
                "인양물 아래로 들어가면 피할 곳이 없습니다");
        assertThat(BriefingViewBuilder.keepPoints(d, slots)).containsExactly(
                "줄걸이 용구와 훅 해지장치를 확인하고 인양합니다",
                "신호수 한 명이 신호하고 인양물 아래 출입을 막습니다",
                "안전모, 안전화를 착용합니다");
    }

    @Test
    void 용접은_불티와_화재감시자() {
        Map<String, String> slots = RiskRuleEngine.withEquipmentKind(Map.of(), "CO2 용접기 2호", "브래킷 용접");
        List<WorkPlanDtos.HazardDecision> d = decide(slots, AccidentType.FIRE, AccidentType.PPE);

        assertThat(BriefingViewBuilder.riskPoints(d, slots)).hasSizeBetween(2, 3)
                .contains("불티가 튀어 주변 가연물에 불이 붙을 수 있습니다");
        assertThat(BriefingViewBuilder.keepPoints(d, slots)).containsExactly(
                "반경 안의 가연물을 치우고 방화포를 덮습니다",
                "화재감시자를 두고 소화기를 옆에 둡니다",
                "보안면, 용접용 장갑을 착용합니다");
    }

    @Test
    void 프레스_금형_교체는_끼임_두_줄과_안전블록() {
        Map<String, String> slots = new HashMap<>(RiskRuleEngine.withEquipmentKind(Map.of(), "기계식 프레스 1호", "금형 교체"));
        slots.put(SlotKeys.GUARD_INSTALLED, "있음");
        List<WorkPlanDtos.HazardDecision> d = decide(slots, AccidentType.CAUGHT, AccidentType.PPE);

        assertThat(BriefingViewBuilder.riskPoints(d, slots)).startsWith("금형 사이에 손이 들어가면 끼일 수 있습니다").hasSize(2);
        assertThat(BriefingViewBuilder.keepPoints(d, slots)).startsWith(
                "금형 교체 전에 전원을 끄고 잠급니다", "안전블록을 끼우고 작업합니다");
    }

    @Test
    void 판정이_보호구뿐이어도_위험_포인트와_지킬_것은_두_개_이상이다() {
        Map<String, String> slots = Map.of();
        List<WorkPlanDtos.HazardDecision> d = decide(slots, AccidentType.PPE);

        assertThat(BriefingViewBuilder.riskPoints(d, slots)).hasSize(2);
        assertThat(BriefingViewBuilder.keepPoints(d, slots)).hasSizeBetween(2, 3);
    }

    @Test
    void 사전조사_항목은_작업계획서에만_있다() {
        assertThat(BriefingViewBuilder.preSurvey(WorkDocument.WORK_PLAN, RiskRuleEngine.KIND_CRANE))
                .contains("인양물 중량과 무게중심");
        assertThat(BriefingViewBuilder.preSurvey(WorkDocument.TBM_CHECKLIST, RiskRuleEngine.KIND_LADDER)).isEmpty();
    }

    @Test
    void 위험_포인트와_지킬_것에_가운뎃점과_대시가_없다() {
        Map<String, String> slots = new HashMap<>(RiskRuleEngine.withEquipmentKind(Map.of(), "이동식 사다리 A", "천장 도장"));
        slots.put(SlotKeys.WORK_HEIGHT, "3.2");
        slots.put(SlotKeys.TOP_STEP, "네");
        slots.put(SlotKeys.TIP_GUARD, "없음");
        slots.put(SlotKeys.PRODUCT_NAME, "유성 에나멜 페인트");
        List<WorkPlanDtos.HazardDecision> d = decide(slots, AccidentType.FALL, AccidentType.FIRE, AccidentType.PPE);
        List<String> all = new ArrayList<>(BriefingViewBuilder.riskPoints(d, slots));
        all.addAll(BriefingViewBuilder.keepPoints(d, slots));
        assertThat(all).hasSizeBetween(4, 6).allSatisfy(t -> assertThat(t).doesNotContain("·").doesNotContain("—").doesNotContain("–"));
    }
}
