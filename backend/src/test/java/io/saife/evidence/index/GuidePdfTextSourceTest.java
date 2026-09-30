package io.saife.evidence.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.saife.evidence.media.MediaCache;
import io.saife.publicapi.domain.KoshaGuide;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MediaCache}는 실제 인스턴스 대신 모킹한다 — 패키지가 달라(io.saife.evidence.media)
 * 테스트 전용 생성자(package-private)에 여기서 접근할 수 없다. 실제 다운로드·리다이렉트
 * 로직은 {@code MediaCacheTest}가 이미 전담한다.
 */
class GuidePdfTextSourceTest {

    @Test
    void fileDownloadUrl_없으면_캐시를_건드리지_않고_빈_리스트() {
        MediaCache cache = mock(MediaCache.class);
        GuidePdfTextSource source = new GuidePdfTextSource(cache);

        KoshaGuide guide = KoshaGuide.builder().guideNo("G-2").guideName("g").build();

        assertThat(source.pagesOf(guide)).isEmpty();
        verifyNoInteractions(cache);
    }

    @Test
    void 캐시_미스면_빈_리스트() {
        MediaCache cache = mock(MediaCache.class);
        when(cache.fetch(eq("guide/G-1"), eq("https://portal.kosha.or.kr/f"), eq("pdf")))
                .thenReturn(Optional.empty());
        GuidePdfTextSource source = new GuidePdfTextSource(cache);

        KoshaGuide guide = KoshaGuide.builder().guideNo("G-1").guideName("g")
                .fileDownloadUrl("https://portal.kosha.or.kr/f").build();

        assertThat(source.pagesOf(guide)).isEmpty();
    }

    @Test
    void 캐시_히트면_PDF_텍스트를_페이지별로_반환(@TempDir Path dir) throws IOException {
        Path pdfFile = dir.resolve("guide.pdf");
        writeTinyPdf(pdfFile, "SAIFE GUIDE TEXT");

        MediaCache cache = mock(MediaCache.class);
        when(cache.fetch(eq("guide/G-3"), eq("https://portal.kosha.or.kr/f3"), eq("pdf")))
                .thenReturn(Optional.of(pdfFile));
        GuidePdfTextSource source = new GuidePdfTextSource(cache);

        KoshaGuide guide = KoshaGuide.builder().guideNo("G-3").guideName("g")
                .fileDownloadUrl("https://portal.kosha.or.kr/f3").build();

        List<String> pages = source.pagesOf(guide);
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0)).contains("SAIFE GUIDE TEXT");
    }

    /**
     * fix round 1(R26): guideNo 검증은 프록시({@code MediaController.sanitizeGuideNo})와
     * 완전히 같은 규칙({@code MediaKeys.isValidGuideNo})을 쓴다 — 치환해서 다른 캐시 키를
     * 만들지 않고, 규칙을 벗어나면 캐시를 아예 건드리지 않고 건너뛴다.
     */
    @Test
    void guideNo가_허용_문자를_벗어나면_본문을_생략한다() {
        MediaCache cache = mock(MediaCache.class);
        GuidePdfTextSource source = new GuidePdfTextSource(cache);

        KoshaGuide guide = KoshaGuide.builder().guideNo("G 12/3").guideName("g")
                .fileDownloadUrl("https://portal.kosha.or.kr/f").build();

        assertThat(source.pagesOf(guide)).isEmpty();
        verifyNoInteractions(cache);
    }

    /** 실제 pdfbox로 만든 1페이지짜리 진짜 PDF. 텍스트는 표준 폰트가 지원하는 라틴 문자로 둔다 */
    private static void writeTinyPdf(Path path, String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(path.toFile());
        }
    }
}
