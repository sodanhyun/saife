package io.saife.ai.vision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 사진 속 위험요인 위치: 모델 응답 파싱과 저장 문자열 */
class PhotoBoxTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void 네_값_0에서_1000_사이로_자르고_뒤집힌_영역은_버린다() throws Exception {
        assertThat(VisionAnalyzer.box(om.readTree("[120, 400, 980, 640]"))).containsExactly(120, 400, 980, 640);
        assertThat(VisionAnalyzer.box(om.readTree("[-5, 400, 1200, 640]"))).containsExactly(0, 400, 1000, 640);
        assertThat(VisionAnalyzer.box(om.readTree("[500, 400, 100, 640]"))).isNull();
        assertThat(VisionAnalyzer.box(om.readTree("[1, 2, 3]"))).isNull();
        assertThat(VisionAnalyzer.box(om.readTree("\"x\""))).isNull();
        assertThat(VisionAnalyzer.box(null)).isNull();
    }

    @Test
    void 저장_문자열과_목록은_서로_바뀐다() {
        String text = VisionAssessmentService.boxText(List.of(120, 400, 980, 640));
        assertThat(text).isEqualTo("120,400,980,640");
        assertThat(VisionAssessmentService.parseBox(text)).containsExactly(120, 400, 980, 640);
        assertThat(VisionAssessmentService.parseBox("a,b")).isNull();
        assertThat(VisionAssessmentService.boxText(null)).isNull();
    }
}
