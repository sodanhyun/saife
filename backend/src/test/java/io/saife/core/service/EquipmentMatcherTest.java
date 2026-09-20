package io.saife.core.service;

import io.saife.core.domain.Equipment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설비 매칭 테스트.
 *
 * <p>여기서 false negative가 나면 같은 설비가 두 ID로 쪼개지고, 그 순간 이 출품작
 * 전체가 기대는 "하나의 설비 ID"가 무너진다. 다른 어떤 테스트보다 이게 중요하다.
 *
 * <p>입력은 <b>모델이 실제로 보낸 문자열</b>을 쓴다 (tool_call.params_json에서 가져왔다).
 * 지어낸 입력으로 통과하는 매칭 테스트는 의미가 없다 — 문제는 늘 모델이 보내는
 * 예상 밖의 모양에서 나온다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EquipmentMatcherTest {

    /** 시드의 가상 사업장 */
    private static final Long SITE = 1L;

    @Autowired
    private EquipmentMatcher matcher;

    @Test
    @DisplayName("장소와 설비가 한 문장으로 와도 한 번에 확정한다")
    void resolvesLocationAndEquipmentInOnePhrase() {
        // 모델이 실제로 보낸 첫 질의
        EquipmentMatcher.MatchResult result =
                matcher.match(SITE, "공장동 후면 차양부 이동식 사다리", null);

        assertThat(result.isConfirmed())
                .as("한 번에 확정되지 않으면 모델이 질의를 쪼개가며 도구를 여러 번 부른다")
                .isTrue();
        assertThat(result.equipment().getName()).isEqualTo("이동식 사다리 A");
    }

    @Test
    @DisplayName("설비명만 와도 확정한다")
    void resolvesEquipmentNameAlone() {
        EquipmentMatcher.MatchResult result = matcher.match(SITE, "이동식 사다리", null);

        assertThat(result.isConfirmed()).isTrue();
        assertThat(result.equipment().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("장소만 오면 확정하지 않는다 — 그 장소에 설비가 둘 있다")
    void doesNotGuessWhenOnlyLocationGiven() {
        // 공장동 후면 차양부에는 이동식 사다리 A와 도장 부스 1호가 있다.
        // 아무거나 고르면 이력이 엉뚱한 설비에 붙는다
        EquipmentMatcher.MatchResult result = matcher.match(SITE, "공장동 후면 차양부", null);

        assertThat(result.process()).isNotNull();
        assertThat(result.process().getLocationTag()).isEqualTo("공장동 후면 차양부");
    }

    @Test
    @DisplayName("모르는 설비는 만들지 않고 미등록으로 돌려준다")
    void doesNotInventEquipment() {
        EquipmentMatcher.MatchResult result = matcher.match(SITE, "존재하지않는설비ZZZ", null);

        assertThat(result.isConfirmed())
                .as("확신 없이 붙인 설비 ID는 조용히 틀리고 나중에 알아차리기 어렵다")
                .isFalse();
    }

    @Test
    @DisplayName("같은 질의는 몇 번을 물어도 같은 설비를 돌려준다")
    void isDeterministic() {
        Long first = null;
        for (int i = 0; i < 5; i++) {
            EquipmentMatcher.MatchResult result =
                    matcher.match(SITE, "공장동 후면 차양부 이동식 사다리", null);
            Long id = result.equipment() == null ? null : result.equipment().getId();
            if (first == null) {
                first = id;
            }
            assertThat(id)
                    .as("정렬이 흔들리면 같은 입력에 다른 설비가 붙고 이력이 쪼개진다")
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("빈 입력에 예외를 던지지 않는다")
    void handlesEmptyQuery() {
        assertThat(matcher.match(SITE, "", null)).isNotNull();
        assertThat(matcher.match(SITE, null, null)).isNotNull();
    }

    @Test
    @DisplayName("확정된 설비는 이름이 비어 있지 않다")
    void confirmedEquipmentHasName() {
        EquipmentMatcher.MatchResult result = matcher.match(SITE, "고소작업대", null);
        if (result.isConfirmed()) {
            Equipment eq = result.equipment();
            assertThat(eq.getName()).isNotBlank();
        }
    }
}
