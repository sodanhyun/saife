package io.saife.evidence.live;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.domain.LawArticle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LawArticleParserTest {

    @Test
    void 항단위로_나누고_장_제목은_건너뛴다() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/fixtures/law-article-36.json"), StandardCharsets.UTF_8);
        List<LawArticle> out = LawArticleParser.parse("001766", "산업안전보건법", json);
        // 36조 ①② + 38조의2 (조문내용) = 3행. 37 '전문'은 제외
        assertThat(out).hasSize(3);
        LawArticle p1 = out.get(0);
        assertThat(p1.getArticleNo()).isEqualTo(36);
        assertThat(p1.getParagraphNo()).isEqualTo(1);
        assertThat(p1.getTitle()).isEqualTo("위험성평가의 실시");
        assertThat(p1.getText()).startsWith("① 사업주는");
        assertThat(p1.getEffectiveOn().toString()).isEqualTo("2026-06-01");
        // F1: 원문 링크는 자격증명 없는 사람용 조문 페이지 — DRF API URL(쿼리에 OC)이 아니다
        assertThat(p1.getSourceUrl()).isEqualTo(LawUrls.articlePage("산업안전보건법", 36, 0));
        assertThat(p1.getSourceUrl()).doesNotContain("OC=").doesNotContain("DRF");
        LawArticle sub = out.get(2);
        assertThat(sub.getArticleNo()).isEqualTo(38);
        assertThat(sub.getArticleSub()).isEqualTo(2);
        assertThat(sub.getParagraphNo()).isZero();
        assertThat(sub.citation()).isEqualTo("산업안전보건법 제38조의2(안전조치 특례)");
        assertThat(sub.getSourceUrl()).endsWith("/" + java.net.URLEncoder.encode("제38조의2", StandardCharsets.UTF_8));
    }

    @Test
    void 깨진_JSON은_빈_리스트() {
        assertThat(LawArticleParser.parse("x", "y", "{not json")).isEmpty();
    }

    @Test
    void 항이_단일_객체면_항_1개로_읽는다() {
        // 조문단위와 마찬가지로 항이 1개뿐이면 배열이 아니라 객체로 온다.
        String json = """
                {"법령":{"조문":{"조문단위":
                  {"조문번호":"5","조문시행일자":"20260601","조문제목":"정의","조문여부":"조문",
                   "항":{"항번호":"①","항내용":"① 이 법에서 사용하는 용어의 뜻은 다음과 같다."}}
                }}}
                """;
        List<LawArticle> out = LawArticleParser.parse("001766", "산업안전보건법", json);
        assertThat(out).hasSize(1);
        LawArticle a = out.get(0);
        assertThat(a.getArticleNo()).isEqualTo(5);
        assertThat(a.getParagraphNo()).isEqualTo(1);
        assertThat(a.getText()).isEqualTo("① 이 법에서 사용하는 용어의 뜻은 다음과 같다.");
    }
}
