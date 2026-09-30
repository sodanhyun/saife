package io.saife.evidence.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MsdsXmlParserTest {

    private String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name), StandardCharsets.UTF_8);
    }

    @Test
    void 목록에서_chemId를_읽는다() throws Exception {
        List<MsdsXmlParser.ChemHit> hits = MsdsXmlParser.parseList(fixture("msds-list.xml"));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chemId()).isEqualTo("001032");
        assertThat(hits.get(0).chemNameKor()).isEqualTo("톨루엔");
        assertThat(hits.get(0).casNo()).isEqualTo("108-88-3");
    }

    @Test
    void 상세에서_항목과_그림문자를_읽는다() throws Exception {
        List<MsdsXmlParser.DetailItem> items = MsdsXmlParser.parseDetail(fixture("msds-detail-02.xml"));
        assertThat(items).hasSize(3);
        assertThat(items.get(0).itemDetail()).startsWith("인화성 액체");
        assertThat(MsdsXmlParser.pictogramsOf(items)).isEqualTo("GHS02,GHS07,GHS08");
    }

    @Test
    void 빈_응답은_빈_리스트() {
        String noItems = "<response><header><resultCode>00</resultCode></header><body><items/></body></response>";
        assertThat(MsdsXmlParser.parseList(noItems)).isEmpty();
        String error = "<response><header><resultCode>30</resultCode><resultMsg>SERVICE KEY IS NOT REGISTERED</resultMsg></header></response>";
        assertThat(MsdsXmlParser.parseDetail(error)).isEmpty();
        assertThat(MsdsXmlParser.parseList("not xml at all")).isEmpty();
    }
}
