package io.saife.evidence.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

class MediaControllerTest {

    @Test
    void DB에_없는_id는_404(@TempDir Path dir) {
        PublicCaseRepository cases = mock(PublicCaseRepository.class);
        when(cases.findById(9L)).thenReturn(Optional.empty());
        MediaController c = new MediaController(new MediaCache(dir, u -> new byte[]{1}), cases, mock(KoshaGuideRepository.class));
        Assertions.assertThrows(NotFoundException.class, () -> c.casePhoto(9L, null));
    }

    @Test
    void 사진이_없는_사례도_404(@TempDir Path dir) {
        PublicCaseRepository cases = mock(PublicCaseRepository.class);
        when(cases.findById(1L)).thenReturn(Optional.of(PublicCase.builder().id(1L).source("DISASTER").sourceKey("k").build()));
        MediaController c = new MediaController(new MediaCache(dir, u -> new byte[]{1}), cases, mock(KoshaGuideRepository.class));
        Assertions.assertThrows(NotFoundException.class, () -> c.casePhoto(1L, null));
    }

    @Test
    void 원본_실패는_캐시하지_않는다(@TempDir Path dir) {
        PublicCaseRepository cases = mock(PublicCaseRepository.class);
        when(cases.findById(1L)).thenReturn(Optional.of(PublicCase.builder().id(1L).source("FATALITY").sourceKey("k")
                .imageUrl("https://portal.kosha.or.kr/a.png").build()));
        AtomicInteger downloads = new AtomicInteger();
        MediaController c = new MediaController(new MediaCache(dir, u -> {
            downloads.incrementAndGet();
            throw new RuntimeException("503");
        }), cases, mock(KoshaGuideRepository.class));

        Assertions.assertThrows(NotFoundException.class, () -> c.casePhoto(1L, null));
        assertThat(Files.exists(dir.resolve("case/1.png"))).isFalse();
        assertThat(downloads.get()).isEqualTo(1);
    }

    @Test
    void 알수없는_지침번호는_404(@TempDir Path dir) {
        KoshaGuideRepository guides = mock(KoshaGuideRepository.class);
        when(guides.findByGuideNo("G-99")).thenReturn(Optional.empty());
        MediaController c = new MediaController(new MediaCache(dir, u -> new byte[]{1}), mock(PublicCaseRepository.class), guides);
        Assertions.assertThrows(NotFoundException.class, () -> c.guidePdf("G-99"));
    }

    @Test
    void 정상이면_PNG와_캐시_헤더(@TempDir Path dir) {
        PublicCaseRepository cases = mock(PublicCaseRepository.class);
        when(cases.findById(1L)).thenReturn(Optional.of(PublicCase.builder().id(1L).source("FATALITY").sourceKey("k")
                .imageUrl("https://portal.kosha.or.kr/a.png").build()));
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        MediaController c = new MediaController(new MediaCache(dir, u -> png), cases, mock(KoshaGuideRepository.class));

        ResponseEntity<Resource> res = c.casePhoto(1L, null);
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(res.getHeaders().getCacheControl()).contains("max-age=86400");
    }

    @Test
    void w파라미터는_썸네일을_반환한다(@TempDir Path dir) throws Exception {
        PublicCaseRepository cases = mock(PublicCaseRepository.class);
        when(cases.findById(2L)).thenReturn(Optional.of(PublicCase.builder().id(2L).source("FATALITY").sourceKey("k")
                .imageUrl("https://portal.kosha.or.kr/b.png").build()));
        byte[] png = realPng(900, 600);
        MediaController c = new MediaController(new MediaCache(dir, u -> png), cases, mock(KoshaGuideRepository.class));

        ResponseEntity<Resource> res = c.casePhoto(2L, 320);
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        assertThat(res.getBody().getFilename()).isEqualTo("2_320.jpg");
    }

    @Test
    void guidePdf는_application_pdf와_inline_헤더(@TempDir Path dir) {
        KoshaGuideRepository guides = mock(KoshaGuideRepository.class);
        when(guides.findByGuideNo("G-2020-1")).thenReturn(Optional.of(
                KoshaGuide.builder().id(1L).guideNo("G-2020-1").guideName("고소작업대 안전지침")
                        .fileDownloadUrl("https://portal.kosha.or.kr/g.pdf").build()));
        byte[] pdf = "%PDF-1.4".getBytes();
        MediaController c = new MediaController(new MediaCache(dir, u -> pdf), mock(PublicCaseRepository.class), guides);

        ResponseEntity<Resource> res = c.guidePdf("G-2020-1");
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(res.getHeaders().getFirst("Content-Disposition")).isEqualTo("inline; filename=\"G-2020-1.pdf\"");
    }

    private byte[] realPng(int w, int h) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
