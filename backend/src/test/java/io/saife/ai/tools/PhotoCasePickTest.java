package io.saife.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 유사 재해사례 3건: 사진 사례가 없으면 사진 사례 전용 검색의 첫 건을 마지막 자리에 넣는다 */
class PhotoCasePickTest {

    private static Evidence ev(int n, boolean photo) {
        return new Evidence(n, EvidenceKind.CASE_FATALITY, (long) n, "k" + n, "사례 " + n, "", null,
                photo ? "/m/" + n : null, photo ? "/m/" + n + "?w=320" : null, Origin.CACHE, 0.9, null, Map.of());
    }

    @Test
    @DisplayName("사진 사례가 없으면 마지막 자리를 사진 사례로")
    void swapsInPhoto() {
        assertThat(HazardAnalysisTools.withPhotoCase(List.of(ev(1, false), ev(2, false), ev(3, false)), List.of(ev(9, true)), 3))
                .extracting(Evidence::refKey).containsExactly("k1", "k2", "k9");
        assertThat(HazardAnalysisTools.withPhotoCase(List.of(ev(1, false)), List.of(ev(9, true)), 3))
                .extracting(Evidence::refKey).containsExactly("k1", "k9");
    }

    @Test
    @DisplayName("이미 사진이 있거나 사진 후보가 없으면 그대로")
    void keepsOrder() {
        assertThat(HazardAnalysisTools.withPhotoCase(List.of(ev(1, false), ev(2, true), ev(3, false)), List.of(ev(9, true)), 3))
                .extracting(Evidence::refKey).containsExactly("k1", "k2", "k3");
        assertThat(HazardAnalysisTools.withPhotoCase(List.of(ev(1, false), ev(2, false), ev(3, false)), List.of(), 3))
                .extracting(Evidence::refKey).containsExactly("k1", "k2", "k3");
    }
}
