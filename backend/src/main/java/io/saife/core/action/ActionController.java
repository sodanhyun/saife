package io.saife.core.action;

import io.saife.common.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 감소대책 이행 확인. 등록은 위험요인 문맥에서 일어나므로
 * {@code POST /api/vision/hazard/{hazardId}/action}에 있다.
 */
@RestController
@RequestMapping("/api/action")
@RequiredArgsConstructor
public class ActionController {

    private final ActionService actionService;
    private final ActionListService actionListService;

    /**
     * 개선대책 목록.
     *
     * @param status OPEN(미이행, 기한 경과 포함, 기본) / OVERDUE / DONE / ALL
     * @param keyword 내용, 담당, 설비명 부분 일치
     */
    @GetMapping
    public ResponseEntity<PageResponse<ActionDtos.ActionListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(actionListService.list(status, keyword, page, size));
    }

    /** 목록 탭 건수 */
    @GetMapping("/counts")
    public ResponseEntity<ActionDtos.ActionCounts> counts() {
        return ResponseEntity.ok(actionListService.counts());
    }

    /** 증빙 사진 첨부와 대조 */
    @PostMapping(value = "/{actionId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ActionDtos.ActionView> attachEvidence(@PathVariable Long actionId,
                                                                @RequestParam("image") MultipartFile image) throws IOException {
        return ResponseEntity.ok(actionService.attachEvidence(actionId, image.getBytes(),
                image.getContentType(), image.getOriginalFilename()));
    }

    /** 증빙 사진 */
    @GetMapping("/{actionId}/evidence")
    public ResponseEntity<FileSystemResource> evidence(@PathVariable Long actionId) {
        return actionService.evidenceFile(actionId)
                .map(p -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache())
                        .contentType(MediaTypeFactory.getMediaType(p.getFileName().toString())
                                .orElse(MediaType.IMAGE_JPEG))
                        .body(new FileSystemResource(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** 이행 확인. 증빙 사진, 확인자, 개선 후 위험성 필수. 멱등 */
    @PostMapping("/{actionId}/verify")
    public ResponseEntity<ActionDtos.ActionView> verify(@PathVariable Long actionId,
                                                        @RequestBody ActionDtos.VerifyActionRequest request) {
        return ResponseEntity.ok(actionService.verify(actionId, request));
    }
}
