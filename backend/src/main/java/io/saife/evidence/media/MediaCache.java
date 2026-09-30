package io.saife.evidence.media;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 원본 URL → 디스크 캐시. 허용 호스트는 공단 포털뿐이다(SSRF 차단 — 클라이언트는 URL을 보낼 수 없고,
 * 컨트롤러가 DB에서 id로 찾은 URL만 여기로 들어온다). 원본 실패·빈 바디·상한 초과는 캐시하지 않는다.
 *
 * <p>리허설에서 시연 사진·PDF가 프리페치로 자동 캐시되므로 무대 오프라인에도 남는다
 * (.claude/rules/deployment.md — 무대 외부 API 호출 0).
 *
 * <p><b>fix round 1(보안 리뷰 R23)</b>: 20MB 다운로드 상한, 리다이렉트 수동 처리(호스트 매 홉 재검증),
 * 캐시 루트 이탈 차단 3건을 추가했다.
 */
@Slf4j
@Component
public class MediaCache {
    static final String ALLOWED_HOST = "portal.kosha.or.kr";

    /** 공단 PDF·사진 최대치에 여유를 더한 다운로드 상한. 초과 응답은 절대 캐시하지 않는다 */
    static final long MAX_BYTES = 20L * 1024 * 1024;

    /** 리다이렉트는 최대 3홉까지만 따라간다. 매 홉마다 호스트를 재검증한다 */
    static final int MAX_REDIRECTS = 3;

    private final Path root;
    private final Function<String, byte[]> downloader;
    private final RawTransport transport;

    @Autowired
    public MediaCache(@Value("${saife.media-dir:./data/media}") String dir) {
        this(Path.of(dir), null, MediaCache::realGet);
    }

    /** 테스트용 — 단순 다운로더를 가짜로 주입해 네트워크를 타지 않는다 (리다이렉트 로직은 거치지 않는다) */
    MediaCache(Path root, Function<String, byte[]> downloader) {
        this(root, downloader, null);
    }

    /**
     * 테스트용 — 리다이렉트·호스트 재검증·상한 로직(fix round 1)을 네트워크 없이 검증하기 위한 seam.
     * 생성자 오버로드로 두면 람다 인자({@code url -> ...})가 {@link Function}과 {@link RawTransport}
     * 양쪽에 모두 적용 가능해 보여 호출부(특히 손대면 안 되는 {@code MediaControllerTest})의 기존
     * {@code new MediaCache(dir, u -> byte[])} 호출이 모호(ambiguous) 컴파일 에러로 깨진다.
     * 그래서 생성자 오버로드 대신 이름이 다른 정적 팩터리로 분리했다.
     */
    static MediaCache forTransport(Path root, RawTransport transport) {
        return new MediaCache(root, null, transport);
    }

    private MediaCache(Path root, Function<String, byte[]> downloader, RawTransport transport) {
        // 캐시 루트 이탈 차단(F3)의 기준점. 한 번만 절대·정규화 경로로 고정해 둔다
        this.root = root.toAbsolutePath().normalize();
        this.downloader = downloader;
        this.transport = transport;
    }

    /**
     * 디스크에 있으면 바로 반환. 없으면 원본을 내려받아 저장한다.
     * 허용 호스트가 아니거나(요청 자체를 하지 않음), 원본이 실패하거나, 빈 바디거나,
     * {@link #MAX_BYTES}를 넘으면 empty를 반환하고 <b>디스크에 아무것도 남기지 않는다.</b>
     * {@code key}가 캐시 루트를 벗어나면(예: {@code "../escape"}) 거부한다(F3).
     */
    public Optional<Path> fetch(String key, String originUrl, String ext) {
        Path file = resolveWithinRoot(key + "." + ext);
        if (file == null) {
            return Optional.empty();
        }
        if (Files.exists(file)) {
            return Optional.of(file);
        }
        if (originUrl == null || !isAllowed(originUrl)) {
            log.warn("[MEDIA] 허용되지 않은 호스트이거나 URL 없음 — 요청하지 않음: {}", originUrl);
            return Optional.empty();
        }
        try {
            // transport가 있으면(운영 기본값) 리다이렉트·호스트 재검증·상한을 거친 경로를 쓰고,
            // 없으면(테스트 seam) 단순 다운로더를 그대로 쓴다 — 어느 쪽이든 아래 크기 검사는 공통으로 탄다
            byte[] body = (transport != null)
                    ? resolveViaTransport(originUrl, transport).orElse(null)
                    : downloader.apply(originUrl);
            if (body == null || body.length == 0) {
                log.warn("[MEDIA] 원본 응답이 비어 있음 — 캐시하지 않음: {}", originUrl);
                return Optional.empty();
            }
            if (body.length > MAX_BYTES) {
                log.warn("[MEDIA] 원본 크기({}B)가 상한({}B)을 초과 — 캐시하지 않음: {}", body.length, MAX_BYTES, originUrl);
                return Optional.empty();
            }
            Files.createDirectories(file.getParent());
            // 부분 쓰기가 캐시로 보이지 않도록 임시 파일에 쓴 뒤 원자적으로 이동한다.
            // 상한 검사를 이미 통과한 바이트만 여기 도달하므로 .part가 상한을 넘겨 쓰인 채
            // 남는 경우는 없다 — 넘으면 위에서 이미 empty로 반환하고 아무 파일도 만들지 않는다
            Path tmp = file.resolveSibling(file.getFileName() + ".part");
            Files.write(tmp, body);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileSystemException fse) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(file);
        } catch (Exception e) {
            log.warn("[MEDIA] 원본 다운로드 실패 {}: {}", originUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * JPEG 썸네일. {@code {name}_{w}.jpg}로 저장하고, 이미 있으면 재생성하지 않고 그대로 반환한다.
     * 폭은 64~1024로 제한한다. 대상 경로가 캐시 루트를 벗어나면 거부한다(F3).
     */
    public Optional<Path> thumbnail(Path original, int width) {
        int w = Math.max(64, Math.min(1024, width));
        String name = original.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path thumb = original.resolveSibling(name + "_" + w + ".jpg").toAbsolutePath().normalize();
        if (!thumb.startsWith(root)) {
            log.warn("[MEDIA] 캐시 루트를 벗어난 썸네일 경로 요청 거부: {}", thumb);
            return Optional.empty();
        }
        if (Files.exists(thumb)) {
            return Optional.of(thumb);
        }
        try {
            BufferedImage src = ImageIO.read(original.toFile());
            if (src == null) {
                return Optional.empty();
            }
            int h = Math.max(1, (int) Math.round(src.getHeight() * (w / (double) src.getWidth())));
            Image scaled = src.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            var g = out.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
            Path tmp = thumb.resolveSibling(thumb.getFileName() + ".part");
            ImageIO.write(out, "jpg", tmp.toFile());
            try {
                Files.move(tmp, thumb, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileSystemException fse) {
                Files.move(tmp, thumb, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(thumb);
        } catch (IOException e) {
            log.warn("[MEDIA] 썸네일 생성 실패 {}: {}", original, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@code root} 기준 상대 키를 절대·정규화 경로로 만들고, 그 결과가 {@code root} 밖으로
     * 나가면(예: {@code "../escape.jpg"}) {@code null}을 반환해 호출자가 거부하게 한다(F3).
     */
    private Path resolveWithinRoot(String relative) {
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            log.warn("[MEDIA] 캐시 루트를 벗어난 경로 요청 거부: {}", relative);
            return null;
        }
        return candidate;
    }

    /** 허용 호스트는 정확히 {@link #ALLOWED_HOST}뿐이다 — 접미사 트릭(예: evil-portal.kosha.or.kr.evil.com),
     * userinfo를 이용한 호스트 혼동(예: {@code https://portal.kosha.or.kr@evil.example/}) 모두 방지 */
    static boolean isAllowed(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && ALLOWED_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 리다이렉트를 수동으로 따라간다(F2). {@link HttpClient}가 자동으로 따라가게 두면 최종 호스트만
     * 검증하게 되기 쉬워 중간 홉이 새는 경로가 생긴다 — 매 홉 진입 전에 {@link #isAllowed(String)}를
     * 다시 통과시키고, 허용 밖이면 <b>그 홉은 요청조차 하지 않는다.</b> 최대 {@value #MAX_REDIRECTS}홉.
     */
    private Optional<byte[]> resolveViaTransport(String url, RawTransport transport) {
        String current = url;
        int redirects = 0;
        while (true) {
            if (!isAllowed(current)) {
                log.warn("[MEDIA] 허용되지 않은 호스트 — 요청하지 않음: {}", current);
                return Optional.empty();
            }
            RawResponse res = transport.get(current);
            boolean isRedirect = res.statusCode() >= 300 && res.statusCode() < 400 && res.location() != null;
            if (isRedirect) {
                if (redirects >= MAX_REDIRECTS) {
                    log.warn("[MEDIA] 리다이렉트 상한({}) 초과 — 중단: {}", MAX_REDIRECTS, url);
                    return Optional.empty();
                }
                String next = URI.create(current).resolve(res.location()).toString();
                if (!isAllowed(next)) {
                    log.warn("[MEDIA] 리다이렉트 목적지가 허용 호스트가 아님 — 중단: {}", next);
                    return Optional.empty();
                }
                current = next;
                redirects++;
                continue;
            }
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("[MEDIA] 원본 응답 실패(status={}): {}", res.statusCode(), current);
                return Optional.empty();
            }
            // Content-Length가 정직하게 왔다면 본문을 굳이 다 갖고 있을 필요 없이 여기서 먼저 거른다.
            // 실제 스트리밍 상한 재확인(거짓 Content-Length 대비)은 realGet()이 읽는 동안 수행한다
            if (res.contentLength() != null && res.contentLength() > MAX_BYTES) {
                log.warn("[MEDIA] Content-Length({}B)가 상한({}B) 초과 — 캐시하지 않음: {}", res.contentLength(), MAX_BYTES, current);
                return Optional.empty();
            }
            return Optional.ofNullable(res.body());
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // 자동 리다이렉트를 끈다 — 매 홉의 호스트를 resolveViaTransport()에서 직접 재검증하기 위해서다(F2)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 실제 원본 요청 1홉. {@link #MAX_BYTES}를 Content-Length로 먼저 거르고, 스트리밍 중에도 러닝 카운터로 재확인한다(F1) */
    private static RawResponse realGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "SAIFE-Evidence-Media/1.0")
                    .GET().build();
            HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int status = res.statusCode();
            String location = res.headers().firstValue("Location").orElse(null);
            Long contentLength = res.headers().firstValueAsLong("Content-Length").isPresent()
                    ? res.headers().firstValueAsLong("Content-Length").getAsLong() : null;

            if (status >= 300 && status < 400) {
                res.body().close();
                return new RawResponse(status, location, contentLength, null);
            }
            if (status < 200 || status >= 300) {
                res.body().close();
                return new RawResponse(status, location, contentLength, null);
            }
            if (contentLength != null && contentLength > MAX_BYTES) {
                log.warn("[MEDIA] Content-Length({}B)가 상한({}B) 초과 — 본문을 읽지 않고 중단: {}", contentLength, MAX_BYTES, url);
                res.body().close();
                return new RawResponse(status, location, contentLength, null);
            }
            try (InputStream in = res.body()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(chunk)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) {
                        log.warn("[MEDIA] 스트리밍 중 상한({}B) 초과 — 중단: {}", MAX_BYTES, url);
                        return new RawResponse(status, location, contentLength, null);
                    }
                    buf.write(chunk, 0, n);
                }
                return new RawResponse(status, location, contentLength, buf.toByteArray());
            }
        } catch (Exception e) {
            log.warn("[MEDIA] 원본 요청 실패 {}: {}", url, e.getMessage());
            return new RawResponse(0, null, null, null);
        }
    }

    /** 원본 응답의 최소 표현. 테스트가 리다이렉트·상한 로직을 네트워크 없이 흉내 낼 수 있도록 분리했다 */
    record RawResponse(int statusCode, String location, Long contentLength, byte[] body) {}

    /** 실제 HTTP 요청 1홉을 수행하는 seam. 운영에서는 {@link #realGet(String)}, 테스트에서는 가짜 구현을 쓴다 */
    @FunctionalInterface
    interface RawTransport {
        RawResponse get(String url);
    }
}
