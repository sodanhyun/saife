package io.saife.core.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.RiskLevel;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 순회점검 기록 상태: 화면과 위험성평가표가 같은 규칙으로 작성 중/확정을 낸다 */
class InspectionRulesTest {

    @Test
    void 허용_가능_기본값은_하만_가능이고_사람이_정한_값이_우선한다() {
        assertThat(InspectionRules.acceptable(null, RiskLevel.HIGH)).isFalse();
        assertThat(InspectionRules.acceptable(null, RiskLevel.MEDIUM)).isFalse();
        assertThat(InspectionRules.acceptable(null, RiskLevel.LOW)).isTrue();
        assertThat(InspectionRules.acceptable(true, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void 참여_근로자가_없으면_작성_중이다() {
        assertThat(InspectionRules.complete(null, List.of())).isFalse();
        assertThat(InspectionRules.complete(" , ", List.of())).isFalse();
    }

    @Test
    void 판단_전이거나_허용_불가인데_대책이_없으면_작성_중이다() {
        assertThat(InspectionRules.complete("김철수", List.of(new InspectionRules.Item(true, null, false, false)))).isFalse();
        assertThat(InspectionRules.complete("김철수", List.of(new InspectionRules.Item(true, true, false, false)))).isFalse();
    }

    @Test
    void 대책이_있거나_허용_가능이거나_제외면_확정이다() {
        assertThat(InspectionRules.complete("김철수, 박민수", List.of(
                new InspectionRules.Item(true, true, false, true),
                new InspectionRules.Item(true, true, true, false),
                new InspectionRules.Item(true, false, false, false),
                new InspectionRules.Item(false, null, false, true)))).isTrue();
    }

    @Test
    void 참여_근로자는_쉼표로_잇고_나눈다() {
        String joined = InspectionRules.joinParticipants(Arrays.asList(" 김철수 ", "", null, "박민수", "김철수"));
        assertThat(joined).isEqualTo("김철수, 박민수");
        assertThat(InspectionRules.splitParticipants(joined)).containsExactly("김철수", "박민수");
        assertThat(InspectionRules.joinParticipants(List.of())).isNull();
        assertThat(InspectionRules.splitParticipants("(자동 생성 초안, 참여자를 입력한 뒤 확정하십시오)")).isEmpty();
    }
}
