package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.live.LawUrls;
import org.junit.jupiter.api.Test;

/** 최종 리뷰 F1 — 시드 적재 시 OC 자격증명 이중 방어 */
class EvidenceSeedLoaderSanitizeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OC = "OC" + "=sample_id";   // 자리표시 값

    @Test
    void 줄_단위로_OC_파라미터를_지운다_메타데이터_안까지() throws Exception {
        String line = "{\"kind\":\"LAW\",\"metadata\":{\"sourceUrl\":\"https://www.law.go.kr/DRF/lawService.do?"
                + OC + "&target=law&MST=1#J36:0\"},\"text\":\"본문?\"}";
        String out = EvidenceSeedLoader.sanitize(line);
        assertThat(out).doesNotContain("OC=");
        JsonNode j = MAPPER.readTree(out);
        assertThat(j.path("metadata").path("sourceUrl").asText())
                .isEqualTo("https://www.law.go.kr/DRF/lawService.do?target=law&MST=1#J36:0");
        assertThat(j.path("text").asText()).isEqualTo("본문?");
    }

    @Test
    void 옛_DRF_조문_링크는_사람용_조문_페이지로_다시_만든다() throws Exception {
        JsonNode drf = MAPPER.readTree(EvidenceSeedLoader.sanitize(
                "{\"lawName\":\"산업안전보건법\",\"articleNo\":36,\"articleSub\":0,"
                        + "\"sourceUrl\":\"https://www.law.go.kr/DRF/lawService.do?" + OC + "&MST=1#J36:0\"}"));
        assertThat(EvidenceSeedLoader.lawSourceUrl(drf)).isEqualTo(LawUrls.articlePage("산업안전보건법", 36, 0));

        String human = LawUrls.articlePage("산업안전보건법", 38, 2);
        JsonNode ok = MAPPER.readTree("{\"lawName\":\"산업안전보건법\",\"articleNo\":38,\"articleSub\":2,\"sourceUrl\":\"" + human + "\"}");
        assertThat(EvidenceSeedLoader.lawSourceUrl(ok)).isEqualTo(human);

        JsonNode none = MAPPER.readTree("{\"lawName\":\"산업안전보건법\",\"articleNo\":5}");
        assertThat(EvidenceSeedLoader.lawSourceUrl(none)).isEqualTo(LawUrls.articlePage("산업안전보건법", 5, 0));
    }
}
