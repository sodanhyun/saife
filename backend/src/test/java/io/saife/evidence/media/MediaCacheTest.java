package io.saife.evidence.media;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.media.MediaCache.RawResponse;
import io.saife.evidence.media.MediaCache.RawTransport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaCacheTest {
    private byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void 첫_요청은_원본_두번째는_디스크(@TempDir Path dir) throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        byte[] body = png(900, 600);
        MediaCache cache = new MediaCache(dir, url -> {
            downloads.incrementAndGet();
            return body;
        });
        Optional<Path> a = cache.fetch("case/1", "https://portal.kosha.or.kr/x.png", "png");
        Optional<Path> b = cache.fetch("case/1", "https://portal.kosha.or.kr/x.png", "png");
        assertThat(a).isPresent();
        assertThat(b).isPresent();
        assertThat(downloads.get()).isEqualTo(1);
        assertThat(Files.size(a.get())).isEqualTo(body.length);
    }

    @Test
    void 원본_실패는_캐시하지_않는다(@TempDir Path dir) {
        MediaCache cache = new MediaCache(dir, url -> {
            throw new RuntimeException("404");
        });
        assertThat(cache.fetch("case/2", "https://portal.kosha.or.kr/y.png", "png")).isEmpty();
        assertThat(Files.exists(dir.resolve("case/2.png"))).isFalse();

        MediaCache empty = new MediaCache(dir, url -> new byte[0]);
        assertThat(empty.fetch("case/3", "https://portal.kosha.or.kr/z.png", "png")).isEmpty();
        assertThat(Files.exists(dir.resolve("case/3.png"))).isFalse();
    }

    @Test
    void 허용_호스트가_아니면_요청하지_않는다(@TempDir Path dir) {
        AtomicInteger downloads = new AtomicInteger();
        MediaCache cache = new MediaCache(dir, url -> {
            downloads.incrementAndGet();
            return new byte[]{1};
        });
        assertThat(cache.fetch("case/4", "https://evil.example/a.png", "png")).isEmpty();
        assertThat(downloads.get()).isZero();
        assertThat(Files.exists(dir.resolve("case/4.png"))).isFalse();
    }

    @Test
    void 접미사_트릭_호스트는_거부한다(@TempDir Path dir) {
        AtomicInteger downloads = new AtomicInteger();
        MediaCache cache = new MediaCache(dir, url -> {
            downloads.incrementAndGet();
            return new byte[]{1};
        });
        assertThat(cache.fetch("case/6", "https://evil-portal.kosha.or.kr.evil.com/a.png", "png")).isEmpty();
        assertThat(downloads.get()).isZero();
    }

    @Test
    void 캐시_히트는_다운로더를_부르지_않는다(@TempDir Path dir) throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        byte[] body = png(10, 10);
        MediaCache cache = new MediaCache(dir, url -> {
            downloads.incrementAndGet();
            return body;
        });
        cache.fetch("case/7", "https://portal.kosha.or.kr/x.png", "png");
        downloads.set(0);
        Optional<Path> second = cache.fetch("case/7", "https://portal.kosha.or.kr/x.png", "png");
        assertThat(second).isPresent();
        assertThat(downloads.get()).isZero();
    }

    @Test
    void 썸네일_생성과_재사용(@TempDir Path dir) throws Exception {
        MediaCache cache = new MediaCache(dir, url -> {
            try {
                return png(900, 600);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Path original = cache.fetch("case/5", "https://portal.kosha.or.kr/x.png", "png").orElseThrow();
        Path t1 = cache.thumbnail(original, 320).orElseThrow();
        assertThat(t1.getFileName().toString()).isEqualTo("5_320.jpg");
        BufferedImage img = ImageIO.read(t1.toFile());
        assertThat(img.getWidth()).isEqualTo(320);

        long mtime = Files.getLastModifiedTime(t1).toMillis();
        Path t2 = cache.thumbnail(original, 320).orElseThrow();
        assertThat(Files.getLastModifiedTime(t2).toMillis()).isEqualTo(mtime);
    }

    // ── fix round 1 (보안 리뷰 R23) ──────────────────────────────────────

    /** F1: 정확히 상한 크기면 캐시된다 */
    @Test
    void 상한_크기와_정확히_같으면_캐시된다(@TempDir Path dir) {
        byte[] body = new byte[(int) MediaCache.MAX_BYTES];
        MediaCache cache = new MediaCache(dir, url -> body);
        Optional<Path> result = cache.fetch("case/max", "https://portal.kosha.or.kr/x.png", "png");
        assertThat(result).isPresent();
    }

    /** F1: 상한을 1바이트라도 넘으면 비고 디스크에 아무것도 남지 않는다 */
    @Test
    void 상한을_넘으면_캐시하지_않는다(@TempDir Path dir) {
        byte[] body = new byte[(int) MediaCache.MAX_BYTES + 1];
        MediaCache cache = new MediaCache(dir, url -> body);
        assertThat(cache.fetch("case/over", "https://portal.kosha.or.kr/x.png", "png")).isEmpty();
        assertThat(Files.exists(dir.resolve("case/over.png"))).isFalse();
    }

    /** 리다이렉트·호스트 검증을 기록하는 가짜 전송 계층 */
    private static class RecordingTransport implements RawTransport {
        final List<String> requested = new ArrayList<>();
        final java.util.Map<String, RawResponse> responses = new java.util.HashMap<>();

        @Override
        public RawResponse get(String url) {
            requested.add(url);
            RawResponse r = responses.get(url);
            return r != null ? r : new RawResponse(404, null, null, null);
        }
    }

    /** F2: 허용 호스트 밖으로 가는 리다이렉트는 그 홉을 요청조차 하지 않는다 */
    @Test
    void 허용호스트_밖_리다이렉트는_요청하지_않고_거부한다(@TempDir Path dir) {
        RecordingTransport transport = new RecordingTransport();
        String start = "https://portal.kosha.or.kr/redirect";
        transport.responses.put(start, new RawResponse(302, "https://evil.example/x", null, null));
        MediaCache cache = MediaCache.forTransport(dir, transport);

        Optional<Path> result = cache.fetch("case/redirect-evil", start, "jpg");

        assertThat(result).isEmpty();
        assertThat(transport.requested).containsExactly(start);
        assertThat(transport.requested).noneMatch(u -> u.contains("evil.example"));
        assertThat(Files.exists(dir.resolve("case/redirect-evil.jpg"))).isFalse();
    }

    /** F2: 허용 호스트 안에서의 리다이렉트는 따라가서 캐시된다 */
    @Test
    void 허용호스트_안_리다이렉트는_따라가서_캐시된다(@TempDir Path dir) {
        RecordingTransport transport = new RecordingTransport();
        String start = "https://portal.kosha.or.kr/redirect2";
        String finalUrl = "https://portal.kosha.or.kr/final.jpg";
        byte[] body = {1, 2, 3, 4};
        transport.responses.put(start, new RawResponse(302, finalUrl, null, null));
        transport.responses.put(finalUrl, new RawResponse(200, null, (long) body.length, body));
        MediaCache cache = MediaCache.forTransport(dir, transport);

        Optional<Path> result = cache.fetch("case/redirect-ok", start, "jpg");

        assertThat(result).isPresent();
        assertThat(transport.requested).containsExactly(start, finalUrl);
        assertThat(Files.exists(dir.resolve("case/redirect-ok.jpg"))).isTrue();
    }

    /** F2: 리다이렉트가 3홉을 넘으면 중단한다 */
    @Test
    void 리다이렉트_3홉_초과는_중단한다(@TempDir Path dir) {
        RecordingTransport transport = new RecordingTransport();
        String u0 = "https://portal.kosha.or.kr/0";
        String u1 = "https://portal.kosha.or.kr/1";
        String u2 = "https://portal.kosha.or.kr/2";
        String u3 = "https://portal.kosha.or.kr/3";
        String u4 = "https://portal.kosha.or.kr/4";
        transport.responses.put(u0, new RawResponse(302, u1, null, null));
        transport.responses.put(u1, new RawResponse(302, u2, null, null));
        transport.responses.put(u2, new RawResponse(302, u3, null, null));
        transport.responses.put(u3, new RawResponse(302, u4, null, null));
        transport.responses.put(u4, new RawResponse(200, null, 1L, new byte[]{1}));
        MediaCache cache = MediaCache.forTransport(dir, transport);

        assertThat(cache.fetch("case/too-many-redirects", u0, "jpg")).isEmpty();
        assertThat(transport.requested).doesNotContain(u4);
    }

    /** F1(경로2): Content-Length가 상한을 넘으면 본문을 받기 전에 거부한다 */
    @Test
    void contentLength가_상한을_넘으면_거부한다(@TempDir Path dir) {
        RecordingTransport transport = new RecordingTransport();
        String url = "https://portal.kosha.or.kr/huge.pdf";
        transport.responses.put(url, new RawResponse(200, null, MediaCache.MAX_BYTES + 1, new byte[]{1}));
        MediaCache cache = MediaCache.forTransport(dir, transport);

        assertThat(cache.fetch("guide/huge", url, "pdf")).isEmpty();
        assertThat(Files.exists(dir.resolve("guide/huge.pdf"))).isFalse();
    }

    /** F3: 캐시 루트를 벗어나는 key는 거부하고 루트 밖에 아무것도 쓰지 않는다 */
    @Test
    void 캐시_루트를_벗어나는_key는_거부한다(@TempDir Path dir) {
        AtomicInteger downloads = new AtomicInteger();
        MediaCache cache = new MediaCache(dir, url -> {
            downloads.incrementAndGet();
            return new byte[]{1};
        });

        Optional<Path> result = cache.fetch("../escape", "https://portal.kosha.or.kr/x.jpg", "jpg");

        assertThat(result).isEmpty();
        assertThat(downloads.get()).isZero();
        assertThat(Files.exists(dir.resolveSibling("escape.jpg"))).isFalse();
    }
}
