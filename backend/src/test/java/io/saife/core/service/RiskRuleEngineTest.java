package io.saife.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;
import io.saife.core.service.RiskRuleEngine.SlotKeys;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 이동식 사다리 판정(안전보건규칙 제42조제4항)과 표기 규칙.
 * 높이 기준은 "2m 이상"(>=), 사다리 상한은 "3.5m 이하"다.
 */
class RiskRuleEngineTest {

    private final RiskRuleEngine engine = new RiskRuleEngine();

    private Map<String, String> ladder(String height, String topStep, String tipGuard) {
        Map<String, String> m = new HashMap<>();
        m.put(SlotKeys.EQUIPMENT_KIND, RiskRuleEngine.KIND_LADDER);
        if (height != null) m.put(SlotKeys.WORK_HEIGHT, height);
        if (topStep != null) m.put(SlotKeys.TOP_STEP, topStep);
        if (tipGuard != null) m.put(SlotKeys.TIP_GUARD, tipGuard);
        return m;
    }

    @Test
    void 시연_답변_3_2m_최상부_하단_디딤대_지지자_없음은_상이고_권고는_이동식_비계다() {
        Map<String, String> slots = ladder("3.2m요", "맨 위 바로 아래 칸까지 올라가요", "따로 잡아주는 사람은 없어요");
        RiskRuleEngine.Decision d = engine.decide(AccidentType.FALL, slots);

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(d.ruleTrace()).isEqualTo(
                "발판 높이 3.2m, 최상부 발판 또는 그 하단 디딤대 사용, 넘어짐 방지(아웃트리거, 고정, 지지자) 없음 (제42조제4항)");
        assertThat(engine.recommendation(AccidentType.FALL, slots, d))
                .isEqualTo("이동식 비계(안전난간) 또는 말비계로 작업발판 확보 (제42조제1항)");
    }

    @Test
    void 발판_높이_3_5m_초과는_사다리_사용_불가로_상() {
        RiskRuleEngine.Decision d = engine.decide(AccidentType.FALL, ladder("3.6", "아니요", "있어요"));
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(d.ruleTrace()).isEqualTo("발판 높이 3.6m, 3.5m 초과로 이동식 사다리 사용 불가 (제42조제4항)");
    }

    @Test
    void 발판_높이_정확히_3_5m는_사용_가능_범위라_조건_충족이면_중() {
        RiskRuleEngine.Decision d = engine.decide(AccidentType.FALL, ladder("3.5", "아니요", "아웃트리거 있어요"));
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(d.ruleTrace()).isEqualTo("발판 높이 3.5m, 안전모, 안전대 착용 (제32조, 제42조제4항)");
    }

    @Test
    void 정확히_2m는_2m_이상으로_본다() {
        RiskRuleEngine.Decision ok = engine.decide(AccidentType.FALL, ladder("2", "아니요", "잡아주는 사람 있어요"));
        assertThat(ok.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(ok.ruleTrace()).contains("안전모, 안전대 착용");

        RiskRuleEngine.Decision top = engine.decide(AccidentType.FALL, ladder("2m", "네, 맨 위까지요", "있어요"));
        assertThat(top.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(top.ruleTrace()).isEqualTo("발판 높이 2m, 최상부 발판 또는 그 하단 디딤대 사용 (제42조제4항)");
    }

    @Test
    void 바로_아래_1_9m는_2m_미만이라_하() {
        RiskRuleEngine.Decision d = engine.decide(AccidentType.FALL, ladder("1.9", "네", "없어요"));
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(d.ruleTrace()).isEqualTo("발판 높이 1.9m (2m 미만), 사용 전 점검 (제42조제4항)");
        assertThat(engine.recommendation(AccidentType.FALL, ladder("1.9", "네", "없어요"), d))
                .isEqualTo("사용 전 점검, 평탄한 바닥에 설치 (제42조제4항)");
    }

    @Test
    void 높이_2m_이상에서_넘어짐_방지만_없어도_상() {
        RiskRuleEngine.Decision d = engine.decide(AccidentType.FALL, ladder("2.5", "아니요", "없습니다"));
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(d.ruleTrace()).isEqualTo("발판 높이 2.5m, 넘어짐 방지(아웃트리거, 고정, 지지자) 없음 (제42조제4항)");
    }

    @Test
    void 높이를_모르면_판정을_미루고_디딤대_답이_없으면_확인_필요() {
        assertThat(engine.decide(AccidentType.FALL, ladder(null, null, null)).ruleTrace())
                .isEqualTo("발판 높이 미확인, 확인 후 판정");
        RiskRuleEngine.Decision d = engine.decide(AccidentType.FALL, ladder("3", null, null));
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(d.ruleTrace()).contains("확인 필요");
    }

    @Test
    void 사다리_슬롯이_있으면_설비_종류를_몰라도_사다리_기준이다() {
        Map<String, String> slots = new HashMap<>(Map.of(SlotKeys.WORK_HEIGHT, "3.2", SlotKeys.TOP_STEP, "네"));
        assertThat(engine.decide(AccidentType.FALL, slots).ruleTrace()).contains("(제42조제4항)");
    }

    @Test
    void 사다리_흐름은_안전대_부착설비_답을_보지_않는다() {
        Map<String, String> slots = ladder("3.2", "아니요", "있어요");
        slots.put(SlotKeys.ANCHOR_INSTALLED, "없음");
        assertThat(engine.decide(AccidentType.FALL, slots).riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void 고소작업대는_제186조_작업대_안전난간_기준() {
        Map<String, String> slots = new HashMap<>(Map.of(SlotKeys.EQUIPMENT_KIND, RiskRuleEngine.KIND_AERIAL_PLATFORM,
                SlotKeys.WORK_HEIGHT, "2.4", SlotKeys.PLATFORM_GUARDRAIL, "일부 없어요"));
        RiskRuleEngine.Decision d = engine.decide(AccidentType.FALL, slots);
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(d.ruleTrace()).isEqualTo("작업대 안전난간 일부 결손 (제186조)");
    }

    @Test
    void 일반_고소작업은_2m_이상에서_안전대_부착설비가_없으면_상() {
        RiskRuleEngine.Decision d = engine.decide(AccidentType.FALL,
                Map.of(SlotKeys.WORK_HEIGHT, "2", SlotKeys.ANCHOR_INSTALLED, "없음"));
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(d.ruleTrace()).isEqualTo("작업 높이 2m (2m 이상), 안전대 부착설비 없음 (제44조)");
    }

    @Test
    void 유성페인트는_인화성_증기_문구로_중이고_보호구에_방독마스크가_들어간다() {
        Map<String, String> slots = ladder("3.2", null, null);
        slots.put(SlotKeys.PRODUCT_NAME, "유성 에나멜 페인트");
        RiskRuleEngine.Decision fire = engine.decide(AccidentType.FIRE, slots);
        assertThat(fire.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(fire.ruleTrace()).isEqualTo("유기용제 도료 사용, 실내 인화성 증기 체류 가능 (제232조)");
        assertThat(engine.decide(AccidentType.PPE, slots).ruleTrace()).isEqualTo("높이 3.2m, 유기용제 취급: 안전모, 안전대, 방독마스크 필요 (제32조, 제450조)");
    }

    @Test
    void 판정_근거에_화살표_따옴표_등급_빈도강도_숫자가_없다() {
        Map<String, String> slots = ladder("3.2", "네", "없음");
        slots.put(SlotKeys.PRODUCT_NAME, "유성페인트");
        for (AccidentType axis : AccidentType.values()) {
            String trace = engine.decide(axis, slots).ruleTrace();
            assertThat(trace).doesNotContain("→", "'상'", "'중'", "'하'", "·", "—", "빈도", "강도");
        }
        assertThat(engine.reassessAfterIncident(RiskLevel.MEDIUM, 5).ruleTrace())
                .isEqualTo("사고 발생으로 위험 실현, 휴업 5일 (3일 이상)")
                .doesNotContain("→", "'");
    }

    @Test
    void 설비_종류는_설비명에서_고른다() {
        assertThat(RiskRuleEngine.equipmentKind("이동식 사다리 A", "천장 페인트 작업")).isEqualTo(RiskRuleEngine.KIND_LADDER);
        assertThat(RiskRuleEngine.equipmentKind("고소작업대", null)).isEqualTo(RiskRuleEngine.KIND_AERIAL_PLATFORM);
        assertThat(RiskRuleEngine.equipmentKind("도장 부스 1호", "도장")).isNull();
    }

    @Test
    void 화면_값은_단위를_붙이고_예_아니요를_정리한다() {
        assertThat(SlotKeys.displayValue(SlotKeys.WORK_HEIGHT, "3.2m요")).isEqualTo("3.2 m");
        assertThat(SlotKeys.displayValue(SlotKeys.WORK_HEIGHT, "사다리 6단, 3.2m")).isEqualTo("3.2 m");
        assertThat(SlotKeys.displayValue(SlotKeys.TOP_STEP, "맨 위 바로 아래 칸까지 올라가요")).isEqualTo("사용");
        assertThat(SlotKeys.displayValue(SlotKeys.TOP_STEP, "아니요, 중간까지만")).isEqualTo("사용 안 함");
        assertThat(SlotKeys.displayValue(SlotKeys.TIP_GUARD, "따로 잡아주는 사람은 없어요")).isEqualTo("없음");
        assertThat(SlotKeys.displayValue(SlotKeys.TIP_GUARD, "아웃트리거 펴고 고정했어요")).isEqualTo("있음");
    }

    @Test
    void 사다리_높이_질문은_현장_말투다() {
        assertThat(SlotKeys.question(SlotKeys.WORK_HEIGHT, RiskRuleEngine.KIND_LADDER))
                .isEqualTo("사다리 발판 높이가 바닥에서 몇 m입니까?");
        assertThat(SlotKeys.question(SlotKeys.TOP_STEP, RiskRuleEngine.KIND_LADDER))
                .isEqualTo("맨 위 발판이나 그 바로 아래 칸에 올라섭니까?");
        assertThat(SlotKeys.question(SlotKeys.PRODUCT_NAME, null)).isEqualTo("페인트 통 라벨의 제품명을 알려 주세요.");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("슬롯 칸에 맞지 않는 답은 저장하지 않는다(최상부 디딤대 칸의 높이 값)")
    void rejectsAnswersForTheWrongSlot() {
        org.assertj.core.api.Assertions.assertThat(RiskRuleEngine.isUsableAnswer(RiskRuleEngine.SlotKeys.TOP_STEP, "3.2m요")).isFalse();
        org.assertj.core.api.Assertions.assertThat(RiskRuleEngine.isUsableAnswer(RiskRuleEngine.SlotKeys.TOP_STEP, "맨 위 바로 아래 칸까지 올라가요")).isTrue();
        org.assertj.core.api.Assertions.assertThat(RiskRuleEngine.isUsableAnswer(RiskRuleEngine.SlotKeys.TIP_GUARD, "따로 잡아주는 사람은 없어요")).isTrue();
        org.assertj.core.api.Assertions.assertThat(RiskRuleEngine.isUsableAnswer(RiskRuleEngine.SlotKeys.WORK_HEIGHT, "높아요")).isFalse();
        org.assertj.core.api.Assertions.assertThat(RiskRuleEngine.isUsableAnswer(RiskRuleEngine.SlotKeys.WORK_HEIGHT, "3.2m요")).isTrue();
    }

    // ── 설비별 판정 기준(2차 개선) ──────────────────────────────────────

    private Map<String, String> derived(String equipmentName, String workName) {
        return RiskRuleEngine.withEquipmentKind(Map.of(), equipmentName, workName);
    }

    @Test
    void 용접_작업과_CO2_용접기는_화재_최소_중이고_근거는_화재감시자다() {
        RiskRuleEngine.Decision byEquipment = engine.decide(AccidentType.FIRE, derived("CO2 용접기 1호", "작업대 보강"));
        assertThat(byEquipment.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(byEquipment.ruleTrace()).isEqualTo("용접 불티 비산, 화재감시자 필요 (제241조, 제241조의2)");

        RiskRuleEngine.Decision byWork = engine.decide(AccidentType.FIRE, derived("천장크레인 1호", "브래킷 용단"));
        assertThat(byWork.riskLevel()).isEqualTo(RiskLevel.MEDIUM);

        Map<String, String> solvent = new HashMap<>(derived("CO2 용접기 1호", "용접"));
        solvent.put(SlotKeys.PRODUCT_NAME, "시너");
        assertThat(engine.decide(AccidentType.FIRE, solvent).riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(engine.recommendation(AccidentType.FIRE, derived("CO2 용접기 1호", "용접"),
                engine.decide(AccidentType.FIRE, derived("CO2 용접기 1호", "용접")))).contains("화재감시자").contains("(제241조, 제241조의2, 제243조)");
    }

    @Test
    void 크레인_인양은_양중기_규정으로_판정한다() {
        Map<String, String> slots = derived("천장크레인 1호", "금형 인양 (1.2톤)");
        assertThat(slots).containsEntry(SlotKeys.EQUIPMENT_KIND, RiskRuleEngine.KIND_CRANE);
        RiskRuleEngine.Decision d = engine.decide(AccidentType.DROP, slots);
        assertThat(d.ruleTrace()).isEqualTo("인양물 낙하 위험, 하부 출입 통제와 훅 해지장치 필요 (제146조, 제137조, 제163조)");
        assertThat(engine.recommendation(AccidentType.DROP, slots, d)).contains("해지장치").contains("제137조");
        assertThat(engine.decide(AccidentType.PPE, slots).ruleTrace()).isEqualTo("인양 작업: 안전모, 안전화 필요 (제32조)");
    }

    @Test
    void 이동식_비계는_제68조_안전난간_바퀴_고정() {
        Map<String, String> slots = new HashMap<>(derived("이동식 비계 1호", "배관 보온"));
        slots.put(SlotKeys.WORK_HEIGHT, "3.6");
        slots.put(SlotKeys.PLATFORM_GUARDRAIL, "있음");
        slots.put(SlotKeys.CASTER_LOCK, "예");
        RiskRuleEngine.Decision ok = engine.decide(AccidentType.FALL, slots);
        assertThat(ok.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(ok.ruleTrace()).isEqualTo("작업발판 높이 3.6m, 안전난간, 바퀴 고정 확인, 안전모 착용 (제68조, 제32조)");

        slots.put(SlotKeys.CASTER_LOCK, "아니요");
        RiskRuleEngine.Decision bad = engine.decide(AccidentType.FALL, slots);
        assertThat(bad.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(bad.ruleTrace()).isEqualTo("작업발판 높이 3.6m, 바퀴 고정 없음 (제68조)");
        assertThat(engine.recommendation(AccidentType.FALL, slots, bad)).contains("(제68조)");
    }

    @Test
    void 프레스_방호장치는_제103조() {
        Map<String, String> slots = new HashMap<>(derived("기계식 프레스 1호", "금형 교체"));
        slots.put(SlotKeys.GUARD_INSTALLED, "없어요");
        RiskRuleEngine.Decision d = engine.decide(AccidentType.CAUGHT, slots);
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(d.ruleTrace()).isEqualTo("프레스 방호장치 없음 (제103조)");
    }

    @Test
    void 지붕_작업은_채광창_제45조() {
        Map<String, String> slots = new HashMap<>(derived("지붕 작업 구역", "채광창 주변 방수 보수"));
        slots.put(SlotKeys.WORK_HEIGHT, "6");
        slots.put(SlotKeys.ANCHOR_INSTALLED, "없음");
        RiskRuleEngine.Decision d = engine.decide(AccidentType.FALL, slots);
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(d.ruleTrace()).isEqualTo("지붕 높이 6m, 채광창 파손 시 떨어짐, 안전대 부착설비 없음 (제45조, 제44조)");
    }

    @Test
    void 권고_대책은_중_하_판정에도_나오고_조문_표기는_한_가지다() {
        Map<String, String> slots = ladder("3.2", "아니요", "있어요");
        slots.put(SlotKeys.PRODUCT_NAME, "유성페인트");
        for (AccidentType axis : AccidentType.values()) {
            RiskRuleEngine.Decision d = engine.decide(axis, slots);
            String rec = engine.recommendation(axis, slots, d);
            assertThat(rec).as(axis + " 권고").isNotBlank();
            assertThat(d.ruleTrace() + rec).doesNotContainPattern("[①-⑳]");
        }
    }
}
