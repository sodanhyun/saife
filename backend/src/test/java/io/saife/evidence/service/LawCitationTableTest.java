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
        assertThat(LawCitationTable.afterIncident()).extracting(LawCitationTable.Citation::articleNo).contains(73, 36);
    }

    @Test
    void 추락은_안전보건규칙_42조가_먼저() {
        LawCitationTable.Citation c = LawCitationTable.forAxis(AccidentType.FALL).get(0);
        assertThat(c.lawName()).isEqualTo(LawCitationTable.RULES);
        assertThat(c.articleNo()).isEqualTo(42);
    }
}
