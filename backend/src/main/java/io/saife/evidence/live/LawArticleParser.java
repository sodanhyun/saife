package io.saife.evidence.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.domain.LawArticle;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 법제처 lawService(target=law, type=JSON) 응답 → 항 단위 {@link LawArticle}.
 *
 * <p>{@code 조문여부=전문}은 편·장 제목이라 건너뛴다. 항이 있으면 항마다 한 행, 없으면 조문내용 한 행(paragraphNo=0).
 *
 * <p>원문 링크는 법제처 목록의 {@code 법령상세링크}(DRF API URL — 쿼리에 OC 자격증명이 박혀 있다)를
 * 쓰지 않고 {@link LawUrls#articlePage}로 사람용 조문 페이지를 만든다(최종 리뷰 F1).
 */
public final class LawArticleParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private LawArticleParser() {}

    public static List<LawArticle> parse(String lawId, String lawName, String json) {
        List<LawArticle> out = new ArrayList<>();
        try {
            JsonNode units = MAPPER.readTree(json).path("법령").path("조문").path("조문단위");
            if (units.isObject()) units = MAPPER.createArrayNode().add(units);
            for (JsonNode u : units) {
                if ("전문".equals(u.path("조문여부").asText(""))) continue;
                int no = u.path("조문번호").asInt(0);
                if (no == 0) continue;
                int sub = u.path("조문가지번호").asInt(0);
                String title = u.path("조문제목").asText(null);
                LocalDate eff = date(u.path("조문시행일자").asText(null));
                String url = LawUrls.articlePage(lawName, no, sub);
                JsonNode paras = u.path("항");
                if (paras.isObject()) paras = MAPPER.createArrayNode().add(paras);
                if (paras.isArray() && !paras.isEmpty()) {
                    int idx = 0;
                    for (JsonNode p : paras) {
                        idx++;
                        String text = p.path("항내용").asText("").trim();
                        if (text.isBlank()) continue;
                        out.add(LawArticle.builder().lawId(lawId).lawName(lawName).articleNo(no).articleSub(sub)
                                .paragraphNo(idx).title(title).text(text).effectiveOn(eff).sourceUrl(url).build());
                    }
                } else {
                    String text = u.path("조문내용").asText("").trim();
                    if (text.isBlank()) continue;
                    out.add(LawArticle.builder().lawId(lawId).lawName(lawName).articleNo(no).articleSub(sub)
                            .paragraphNo(0).title(title).text(text).effectiveOn(eff).sourceUrl(url).build());
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static LocalDate date(String ymd) {
        try { return ymd == null ? null : LocalDate.parse(ymd, YMD); } catch (Exception e) { return null; }
    }
}
