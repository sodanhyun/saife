package io.saife.evidence.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 법제처 OPEN API. serviceKey가 아니라 OC(기관코드)를 쓴다.
 * OC가 들어간 요청 URL은 이 클래스 밖으로 내보내지 않는다(로그·예외 메시지 포함).
 */
@Component
public class LawClient {
    /**
     * 목록 응답의 {@code 법령상세링크}는 일부러 담지 않는다 — DRF API URL이라 쿼리에 OC가 박혀 있고,
     * 저장·표시 경로로 새면 되돌릴 수 없다(최종 리뷰 F1). 원문 링크는 {@link LawUrls#articlePage}.
     */
    public record LawMeta(String lawId, String mst, String lawName) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String oc;

    public LawClient(@Value("${public-api.law.base-url}") String baseUrl,
                     @Value("${public-api.law.oc:}") String oc) {
        this.baseUrl = baseUrl; this.oc = oc;
    }

    public boolean keyPresent() { return oc != null && !oc.isBlank(); }

    /** 법령명으로 현행 법령의 MST·ID·상세링크를 찾는다. 정확히 같은 이름을 우선 */
    public Optional<LawMeta> findLawMst(String lawName) throws Exception {
        String url = baseUrl + "/lawSearch.do?OC=" + oc + "&target=law&type=JSON&query=" + enc(lawName);
        JsonNode laws = MAPPER.readTree(get(url)).path("LawSearch").path("law");
        if (laws.isObject()) laws = MAPPER.createArrayNode().add(laws);
        JsonNode best = null;
        for (JsonNode l : laws) {
            if (lawName.equals(l.path("법령명한글").asText("")) && "현행".equals(l.path("현행연혁코드").asText(""))) { best = l; break; }
            if (best == null && "현행".equals(l.path("현행연혁코드").asText(""))) best = l;
        }
        if (best == null) return Optional.empty();
        return Optional.of(new LawMeta(best.path("법령ID").asText(), best.path("법령일련번호").asText(),
                best.path("법령명한글").asText()));
    }

    /** 법령 전체 조문 JSON (MST 기준 1회 호출) */
    public String fetchLawJson(String mst) throws Exception {
        return get(baseUrl + "/lawService.do?OC=" + oc + "&target=law&type=JSON&MST=" + enc(mst));
    }

    /** 조문 1개 (런타임 시행일 확인용). JO는 6자리: 조번호 4자리 + 가지번호 2자리 */
    public String fetchArticleJson(String lawName, int articleNo, int articleSub) throws Exception {
        String jo = String.format("%04d%02d", articleNo, articleSub);
        return get(baseUrl + "/lawService.do?OC=" + oc + "&target=law&type=JSON&LM=" + enc(lawName) + "&JO=" + jo);
    }

    private String get(String url) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(7)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
        return res.body();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
