package io.saife.core.action;

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

    /** 이행 완료. 멱등 — 이미 완료면 그대로 돌려준다 */
    @PostMapping("/{actionId}/complete")
    public ResponseEntity<ActionDtos.ActionView> complete(@PathVariable Long actionId) {
        return ResponseEntity.ok(actionService.complete(actionId));
    }
}
