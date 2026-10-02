package io.saife.core.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import org.junit.jupiter.api.Test;

/** 감소대책 초안은 축과 빠진 조치로 고정 표에서 나온다. 같은 입력이면 같은 문안이어야 한다 */
class ActionSuggestionTableTest {

    @Test
    void 안전대_부착설비_미설치는_앵커_설치와_제44조() {
        ActionSuggestionTable.Suggestion s =
                ActionSuggestionTable.suggest(AccidentType.FALL, "안전대 부착설비 미설치", "C-31-2017");

        assertThat(s.content()).contains("안전대 부착설비(앵커");
        assertThat(s.lawRef()).isEqualTo("산업안전보건기준에 관한 규칙 제44조");
        assertThat(s.lawTitle()).isEqualTo("안전대의 부착설비 등");
        assertThat(s.guideRef()).isEqualTo("C-31-2017");
    }

    @Test
    void 안전모_미착용은_착용_지도와_착용_확인() {
        ActionSuggestionTable.Suggestion s =
                ActionSuggestionTable.suggest(AccidentType.PPE, "안전모 미착용", null);

        assertThat(s.content()).isEqualTo("안전모 지급, 착용 지도와 작업 전 착용 확인");
        assertThat(s.lawRef()).endsWith("제32조");
        assertThat(s.guideRef()).isNull();
    }

    @Test
    void 같은_축이라도_빠진_조치로_갈린다() {
        assertThat(ActionSuggestionTable.suggest(AccidentType.FALL, "개구부 덮개 미설치", null).lawRef())
                .endsWith("제43조");
        assertThat(ActionSuggestionTable.suggest(AccidentType.FALL, "작업발판 안전난간 미설치", null).content())
                .contains("안전난간");
        assertThat(ActionSuggestionTable.suggest(AccidentType.PPE, "안전대 미착용", null).content())
                .contains("안전대(안전그네)");
    }

    @Test
    void 표에_없는_문구는_축_기본_문안으로_떨어진다() {
        ActionSuggestionTable.Suggestion s =
                ActionSuggestionTable.suggest(AccidentType.FALL, "처음 보는 문구", null);

        assertThat(s).isNotNull();
        assertThat(s.lawRef()).endsWith("제42조");
    }

    @Test
    void 화면에_뜨는_조문_제목에는_대시나_가운뎃점이_없다() {
        for (AccidentType axis : AccidentType.values()) {
            ActionSuggestionTable.Suggestion s = ActionSuggestionTable.suggest(axis, "", null);
            assertThat(s).as(axis.name()).isNotNull();
            assertThat(s.content()).doesNotContain("·", "—", "–");
            if (s.lawTitle() != null) {
                assertThat(s.lawTitle()).doesNotContain("·", "—", "–");
            }
        }
    }

    @Test
    void 사다리_최상부_디딤대는_이동식_비계가_1순위이고_제42조제4항_문언을_쓴다() {
        ActionSuggestionTable.Suggestion s =
                ActionSuggestionTable.suggest(AccidentType.FALL, "최상부 디딤대 사용", null);

        assertThat(s.content()).startsWith("이동식 비계(안전난간)");
        assertThat(s.content()).contains("최상부 발판 및 그 하단 디딤대 사용 금지");
        assertThat(s.lawRef()).endsWith("제42조제4항");
        assertThat(s.priority()).isEqualTo(ControlPriority.ENGINEERING);
    }

    @Test
    void 작업발판_미확보도_이동식_비계로_간다() {
        ActionSuggestionTable.Suggestion s =
                ActionSuggestionTable.suggest(AccidentType.FALL, "작업발판 미확보", null);

        assertThat(s.content()).startsWith("이동식 비계");
        assertThat(s.lawRef()).endsWith("제42조제1항");
    }

    @Test
    void 조문_매핑은_원문_조문을_따른다() {
        assertThat(ActionSuggestionTable.suggest(AccidentType.STRUCK, "통로 폐색", null).lawRef()).endsWith("제22조");
        assertThat(ActionSuggestionTable.suggest(AccidentType.DROP, "적재 불량", null).lawRef()).endsWith("제393조");
        assertThat(ActionSuggestionTable.suggest(AccidentType.FIRE, "소화기 미비치", null).lawRef()).endsWith("제243조");
        assertThat(ActionSuggestionTable.suggest(AccidentType.FIRE, "", null).lawRef()).endsWith("제232조");
        assertThat(ActionSuggestionTable.suggest(AccidentType.PPE, "안전모 미착용", null).priority())
                .isEqualTo(ControlPriority.PPE);
    }

    @Test
    void 축이_없으면_초안도_없다() {
        assertThat(ActionSuggestionTable.suggest(null, "안전모 미착용", null)).isNull();
    }
}
