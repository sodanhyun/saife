package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfTextExtractorTest {

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : List.of("Page one text", "Page two text")) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(line);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void 페이지_순서대로_텍스트를_뽑는다() throws Exception {
        List<String> pages = PdfTextExtractor.extract(twoPagePdf());
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).contains("Page one");
        assertThat(pages.get(1)).contains("Page two");
    }

    @Test
    void 깨진_바이트는_빈_리스트() {
        assertThat(PdfTextExtractor.extract("not a pdf".getBytes())).isEmpty();
    }

    @Test
    void 품질_점수는_한글_라틴이_높고_깨진_문자가_낮다() {
        assertThat(PdfTextExtractor.quality("안전난간을 설치한다 install guard")).isGreaterThan(0.9);
        assertThat(PdfTextExtractor.quality("����")).isLessThan(0.3);
        assertThat(PdfTextExtractor.quality("")).isZero();
    }

    @Test
    void stripControlChars는_NUL과_제어문자를_지우고_개행은_보존한다() {
        assertThat(PdfTextExtractor.stripControlChars("a\u0000b\u0007c\n")).isEqualTo("abc\n");
    }

    @Test
    void 추출된_텍스트에는_제어문자가_없다() throws Exception {
        List<String> pages = PdfTextExtractor.extract(twoPagePdf());
        for (String page : pages) {
            assertThat(page).doesNotContainPattern("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");
        }
    }
}
