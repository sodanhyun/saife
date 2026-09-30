package io.saife.evidence.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saife.evidence.live.MsdsLiveClient.MsdsBundle;
import io.saife.publicapi.domain.MsdsCache;
import io.saife.publicapi.repository.MsdsCacheRepository;
import io.saife.publicapi.service.MsdsResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MsdsLiveClientTest {

    private static final String BASE_URL = "https://apis.data.go.kr/B552468";
    private static final String LIST_PATH = "/msdschem1/getChemList001";
    private static final String DETAIL_PATH = "/msdschem1/getChemDetail0{section}1";
    private static final String EMPTY_ITEMS_XML =
            "<response><header><resultCode>00</resultCode></header><body><items/></body></response>";

    private String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name), StandardCharsets.UTF_8);
    }

    private LiveOrCache newLiveOrCache() {
        return new LiveOrCache(Duration.ofSeconds(2), 3, Duration.ofSeconds(60));
    }

    /** detailPath.replace("{section}", "02") 등 실제 클라이언트가 만드는 URL 조각과 동일하게 계산한다 */
    private static String detailSegment(String section) {
        return DETAIL_PATH.replace("{section}", section);
    }

    @Test
    void 라이브_경로_목록과_02항목만_있어도_번들을_조립하고_저장한다() throws Exception {
        String listXml = fixture("msds-list.xml");
        String detail02Xml = fixture("msds-detail-02.xml");

        MsdsCacheRepository repository = mock(MsdsCacheRepository.class);
        MsdsResolver resolver = mock(MsdsResolver.class);
        when(resolver.guessIngredients(anyString())).thenReturn(List.of());

        Function<String, String> httpGet = url -> {
            if (url.contains(LIST_PATH)) return listXml;
            if (url.contains(detailSegment("02"))) return detail02Xml;
            return EMPTY_ITEMS_XML; // 05/07/08
        };

        MsdsLiveClient client = new MsdsLiveClient(repository, resolver, newLiveOrCache(),
                BASE_URL, LIST_PATH, DETAIL_PATH, "test-key", httpGet);

        Fetched<MsdsBundle> f = client.resolve("톨루엔");

        assertThat(f.origin()).isEqualTo(Origin.LIVE);
        assertThat(f.value().chemId()).isEqualTo("001032");
        assertThat(f.value().pictograms()).isEqualTo("GHS02,GHS07,GHS08");
        // msds-detail-02.xml에는 <item> 3개(B02 분류·B04 그림문자·B05 신호어)가 들어 있고
        // sections는 한 섹션에 속한 모든 item의 itemDetail을 '|' 기준으로 펼쳐 합친다 → 2+3+1=6줄
        assertThat(f.value().sections().get("02")).containsExactly(
                "인화성 액체 : 구분2", "피부 부식성/피부 자극성 : 구분2",
                "GHS02.gif", "GHS07.gif", "GHS08.gif", "위험");
        // 섹션 순서가 02,05,07,08로 조립되는지 (LinkedHashMap 순회 순서)
        assertThat(f.value().sections().keySet()).containsExactly("02", "05", "07", "08");

        verify(repository, atLeastOnce()).save(org.mockito.ArgumentMatchers.any(MsdsCache.class));
    }

    @Test
    void 상세_한_섹션_호출이_실패해도_나머지_섹션은_채워진다() throws Exception {
        String listXml = fixture("msds-list.xml");
        String detail02Xml = fixture("msds-detail-02.xml");

        MsdsCacheRepository repository = mock(MsdsCacheRepository.class);
        MsdsResolver resolver = mock(MsdsResolver.class);
        when(resolver.guessIngredients(anyString())).thenReturn(List.of());

        Function<String, String> httpGet = url -> {
            if (url.contains(LIST_PATH)) return listXml;
            if (url.contains(detailSegment("02"))) return detail02Xml;
            if (url.contains(detailSegment("05"))) throw new RuntimeException("네트워크 오류");
            return EMPTY_ITEMS_XML; // 07/08
        };

        MsdsLiveClient client = new MsdsLiveClient(repository, resolver, newLiveOrCache(),
                BASE_URL, LIST_PATH, DETAIL_PATH, "test-key", httpGet);

        Fetched<MsdsBundle> f = client.resolve("톨루엔");

        assertThat(f.origin()).isEqualTo(Origin.LIVE);
        assertThat(f.value().sections().get("02")).hasSize(6);
        assertThat(f.value().sections().get("05")).isEmpty();
    }

    @Test
    void 키가_없으면_라이브를_호출하지_않고_캐시에서_읽는다() {
        MsdsCacheRepository repository = mock(MsdsCacheRepository.class);
        MsdsResolver resolver = mock(MsdsResolver.class);
        when(resolver.guessIngredients(anyString())).thenReturn(List.of());
        when(resolver.resolveChemId("톨루엔")).thenReturn("001032");

        OffsetDateTime now = OffsetDateTime.now();
        MsdsCache row02 = MsdsCache.builder().chemId("001032").chemNameKor("톨루엔").casNo("108-88-3")
                .unNo("1294").sectionCode("02").itemCode("B02").itemName("유해성·위험성")
                .itemDetail("인화성 액체 : 구분2|피부 자극성 : 구분2").pictograms("GHS02,GHS07").fetchedAt(now).build();
        MsdsCache row05 = MsdsCache.builder().chemId("001032").chemNameKor("톨루엔").casNo("108-88-3")
                .unNo("1294").sectionCode("05").itemCode("B10").itemName("폭발·화재시 대처방법")
                .itemDetail("포·이산화탄소 사용").fetchedAt(now).build();
        when(repository.findByChemId("001032")).thenReturn(List.of(row02, row05));

        Function<String, String> httpGetNeverCalled = url -> {
            throw new AssertionError("키가 없으면 httpGet이 호출되면 안 된다: " + url);
        };

        MsdsLiveClient client = new MsdsLiveClient(repository, resolver, newLiveOrCache(),
                BASE_URL, LIST_PATH, DETAIL_PATH, "", httpGetNeverCalled);

        Fetched<MsdsBundle> f = client.resolve("톨루엔");

        assertThat(f.origin()).isEqualTo(Origin.CACHE);
        assertThat(f.value().chemId()).isEqualTo("001032");
    }

    @Test
    void 키도_없고_캐시도_없으면_빈결과() {
        MsdsCacheRepository repository = mock(MsdsCacheRepository.class);
        MsdsResolver resolver = mock(MsdsResolver.class);
        when(resolver.guessIngredients(anyString())).thenReturn(List.of());
        when(resolver.resolveChemId(anyString())).thenReturn(null);

        Function<String, String> httpGetNeverCalled = url -> {
            throw new AssertionError("키가 없으면 httpGet이 호출되면 안 된다: " + url);
        };

        MsdsLiveClient client = new MsdsLiveClient(repository, resolver, newLiveOrCache(),
                BASE_URL, LIST_PATH, DETAIL_PATH, "", httpGetNeverCalled);

        Fetched<MsdsBundle> f = client.resolve("존재하지않는물질");

        assertThat(f.isEmpty()).isTrue();
    }
}
