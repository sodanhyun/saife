package io.saife.evidence.media;

import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사진·PDF 온디맨드 프록시.
 *
 * <p><b>클라이언트는 URL을 보내지 않는다.</b> {@code caseId}·{@code guideNo}로 DB 행을 찾고
 * 그 행의 {@code imageUrl}/{@code fileDownloadUrl}만 {@link MediaCache}로 넘긴다. 그 외
 * 쿼리 파라미터는 {@code w}(썸네일 폭) 하나뿐이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {
    private static final int DEFAULT_THUMB_WIDTH = 320;
    private static final int MIN_THUMB_WIDTH = 64;
    private static final int MAX_THUMB_WIDTH = 1024;

    private final MediaCache cache;
    private final PublicCaseRepository cases;
    private final KoshaGuideRepository guides;

    /** {@code ?w=}가 없으면 원본, 있으면 320px(기본) JPEG 썸네일 */
    @GetMapping("/case/{caseId}/photo")
    public ResponseEntity<Resource> casePhoto(@PathVariable Long caseId,
                                              @RequestParam(value = "w", required = false) Integer w) {
        PublicCase c = cases.findById(caseId)
                .orElseThrow(() -> new NotFoundException("사례를 찾을 수 없습니다: " + caseId));
        if (c.getImageUrl() == null) {
            throw new NotFoundException("이 사례에는 사진이 없습니다: " + caseId);
        }
        Path original = cache.fetch("case/" + caseId, c.getImageUrl(), "png")
                .orElseThrow(() -> new NotFoundException("사진 원본을 가져오지 못했습니다: " + caseId));

        if (w != null) {
            int width = resolveWidth(w);
            Path thumb = cache.thumbnail(original, width).orElse(null);
            if (thumb != null) {
                return respond(thumb, MediaType.IMAGE_JPEG, null);
            }
            // 썸네일 생성에 실패해도 원본은 있으므로 원본을 돌려준다
            log.warn("[MEDIA] 썸네일 생성 실패 — 원본으로 대체: case={} w={}", caseId, width);
        }
        return respond(original, sniffImageType(original), null);
    }

    /** PDF는 항상 원본 그대로. inline으로 열려 클릭 한 번에 뷰어에서 보인다 */
    @GetMapping("/guide/{guideNo}.pdf")
    public ResponseEntity<Resource> guidePdf(@PathVariable String guideNo) {
        KoshaGuide g = guides.findByGuideNo(guideNo)
                .orElseThrow(() -> new NotFoundException("지침을 찾을 수 없습니다: " + guideNo));
        String safeGuideNo = sanitizeGuideNo(guideNo);
        Path file = cache.fetch("guide/" + safeGuideNo, g.getFileDownloadUrl(), "pdf")
                .orElseThrow(() -> new NotFoundException("PDF 원본을 가져오지 못했습니다: " + guideNo));
        return respond(file, MediaType.APPLICATION_PDF, "inline; filename=\"" + safeGuideNo + ".pdf\"");
    }

    /**
     * {@code guideNo}는 경로 세그먼트로 쓰인다 — 허용 문자 밖은 전부 404로 처리해 경로 조작을 막는다.
     * 판정 규칙은 {@link MediaKeys#isValidGuideNo(String)}로 옮겨 {@code GuidePdfTextSource}(인덱스
     * 빌드)와 공유한다 — 두 경로가 같은 {@code "guide/{guideNo}"} 캐시 키를 쓰므로 판정도 같아야 한다.
     */
    private String sanitizeGuideNo(String guideNo) {
        if (!MediaKeys.isValidGuideNo(guideNo)) {
            throw new NotFoundException("지침 번호 형식이 올바르지 않습니다: " + guideNo);
        }
        return guideNo;
    }

    private int resolveWidth(Integer w) {
        if (w == null || w <= 0) {
            return DEFAULT_THUMB_WIDTH;
        }
        return Math.max(MIN_THUMB_WIDTH, Math.min(MAX_THUMB_WIDTH, w));
    }

    /** 매직 바이트로 판별한다 — 저장 확장자가 실제 포맷과 다를 수 있다(원본은 항상 ".png" 키로 저장됨) */
    private MediaType sniffImageType(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(12);
            if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return MediaType.IMAGE_PNG;
            }
            if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
                return MediaType.IMAGE_JPEG;
            }
            if (head.length >= 4 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
                return MediaType.valueOf("image/gif");
            }
        } catch (IOException e) {
            log.warn("[MEDIA] 콘텐츠 타입 판별 실패 — 기본값(image/jpeg) 사용: {}", e.getMessage());
        }
        return MediaType.IMAGE_JPEG;
    }

    private ResponseEntity<Resource> respond(Path file, MediaType type, String disposition) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic());
        if (disposition != null) {
            b.header("Content-Disposition", disposition);
        }
        return b.body(new FileSystemResource(file));
    }
}
