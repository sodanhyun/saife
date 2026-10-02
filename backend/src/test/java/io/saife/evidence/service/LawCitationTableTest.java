package io.saife.evidence.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import org.junit.jupiter.api.Test;

class LawCitationTableTest {
    @Test
    void 모든_축에_최소_1건_공통_2건() {
        for (AccidentType axis : AccidentType.values()) {
            assertThat(LawCitationTable.forAxis(axis)).isNotEmpty();
        }
        assertThat(LawCitationTable.common()).hasSize(2);
        assertThat(LawCitationTable.afterIncident()).extracting(LawCitationTable.Citation::articleNo).contains(73, 37);
    }

    @Test
    void 추락은_안전보건규칙_42조가_먼저() {
        LawCitationTable.Citation c = LawCitationTable.forAxis(AccidentType.FALL).get(0);
        assertThat(c.lawName()).isEqualTo(LawCitationTable.RULES);
        assertThat(c.articleNo()).isEqualTo(42);
    }

    @Test
    void 화면에_뜨는_조문_설명에_가운뎃점과_대시가_없다() {
        java.util.List<LawCitationTable.Citation> all = new java.util.ArrayList<>();
        for (AccidentType axis : AccidentType.values()) all.addAll(LawCitationTable.forAxis(axis));
        all.addAll(LawCitationTable.common());
        all.addAll(LawCitationTable.afterIncident());
        assertThat(all).extracting(LawCitationTable.Citation::why)
                .allSatisfy(w -> assertThat(w).doesNotContain("·", "ㆍ", "—", "–", " - "));
    }

    @Test
    void 사고_후_보고는_사망_또는_3일_이상_휴업_기준을_적는다() {
        assertThat(LawCitationTable.afterIncident().get(0).why()).contains("사망 또는 3일 이상 휴업이 필요한");
    }
}
