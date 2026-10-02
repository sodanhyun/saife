package io.saife.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 재해사례 3건 선택: 상위에 사진이 없으면 후보 풀의 사진 사례 1건을 마지막 자리에 넣는다 */
class PhotoCasePickTest {

    private static Evidence ev(int n, boolean photo) {
        return new Evidence(n, EvidenceKind.CASE_FATALITY, (long) n, "k" + n, "사례 " + n, "", null,
                photo ? "/m/" + n : null, photo ? "/m/" + n + "?w=320" : null, Origin.CACHE, 0.9, null, Map.of());
    }

    @Test
    @DisplayName("상위 3건에 사진이 없으면 풀의 첫 사진 사례가 3번째 자리로")
    void swapsInPhoto() {
        List<Evidence> picked = HazardAnalysisTools.withPhotoCase(
                List.of(ev(1, false), ev(2, false), ev(3, false), ev(4, false), ev(5, true), ev(6, true)), 3);
        assertThat(picked).extracting(Evidence::refKey).containsExactly("k1", "k2", "k5");
    }

    @Test
    @DisplayName("상위에 사진이 있거나 풀에 사진이 없으면 그대로")
    void keepsOrder() {
        assertThat(HazardAnalysisTools.withPhotoCase(List.of(ev(1, false), ev(2, true), ev(3, false), ev(4, true)), 3))
                .extracting(Evidence::refKey).containsExactly("k1", "k2", "k3");
        assertThat(HazardAnalysisTools.withPhotoCase(List.of(ev(1, false), ev(2, false), ev(3, false), ev(4, false)), 3))
                .extracting(Evidence::refKey).containsExactly("k1", "k2", "k3");
    }
}
