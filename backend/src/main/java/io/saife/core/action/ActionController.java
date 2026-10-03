package io.saife.core.action;

import io.saife.common.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 감소대책 이행. 등록은 위험요인 문맥에서 일어나므로
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

    /** 이행 완료. 멱등 — 이미 완료면 그대로 돌려준다 */
    @PostMapping("/{actionId}/complete")
    public ResponseEntity<ActionDtos.ActionView> complete(@PathVariable Long actionId) {
        return ResponseEntity.ok(actionService.complete(actionId));
    }
}
