package io.saife.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;
import org.junit.jupiter.api.Test;

/** 사진 등급표: 사다리는 제42조④의 관찰 가능한 항목으로 판정하고, 근거 문장은 짧은 평문이다 */
class PhotoRiskTableTest {

    private final PhotoRiskTable table = new PhotoRiskTable();

    @Test
    void 최상부_디딤대_사용은_상이고_제42조제4항_문언을_쓴다() {
        RiskRuleEngine.Decision d = table.decide(AccidentType.FALL, "최상부 디딤대 사용");

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(d.ruleTrace()).startsWith("이동식 사다리 최상부 발판 및 그 하단 디딤대 사용 금지 (제42조제4항)");
        assertThat(d.ruleTrace()).endsWith("(사진 기준)");
    }

    @Test
    void 사다리_장면은_표현이_달라도_같은_위험요인으로_묶인다() {
        assertThat(table.categoryOf(AccidentType.FALL, "최상부 디딤대 사용"))
                .isEqualTo(table.categoryOf(AccidentType.FALL, "작업발판 미확보"))
                .isEqualTo("FALL_LADDER");
        // 작업발판 안전난간은 다른 위험요인이다
        assertThat(table.categoryOf(AccidentType.FALL, "작업발판 안전난간 미설치")).isEqualTo("FALL_GUARDRAIL");
    }

    @Test
    void 근거_문장에는_따옴표_등급이나_화살표_대시_가운뎃점이_없다() {
        String[][] cases = {
                {"FALL", "최상부 디딤대 사용"}, {"FALL", "안전대 부착설비 미설치"}, {"FALL", "개구부 덮개 미설치"},
                {"CAUGHT", "방호덮개 미설치"}, {"DROP", "적재 불량"}, {"STRUCK", "통로 폐색"},
                {"FIRE", "소화기 미비치"}, {"PPE", "안전모 미착용"}, {"PPE", "처음 보는 문구"},
        };
        for (String[] c : cases) {
            String trace = table.decide(AccidentType.valueOf(c[0]), c[1]).ruleTrace();
            assertThat(trace).as(c[1]).doesNotContain("'", "→", "—", "–", "·", "빈도 ", "축");
        }
    }
}
