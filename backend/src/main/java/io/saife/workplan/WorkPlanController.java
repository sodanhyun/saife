package io.saife.workplan;

import io.saife.common.dto.PageResponse;
import io.saife.common.web.PageRequests;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.dto.WorkPlanDtos;
import io.saife.workplan.service.WorkPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * UC3 작업계획서 — 승인 큐와 브리핑 확인.
 *
 * <p>{@code POST /{id}/ack}가 이 컨트롤러의 존재 이유다. 작업자가 브리핑을 확인한
 * 시각이 {@code work_plan.briefing_ack_at}에 남고, 그게 두 가지를 동시에 떠받친다:
 *
 * <ul>
 *   <li><b>상시평가 트랙의 TBM 요건</b> — 구두 TBM과 달리 확인 기록이 남는다</li>
 *   <li><b>UC2의 가장 강한 한 줄</b> — "작업 전 브리핑으로 경고까지 전달됐습니다"</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/work-plan")
@RequiredArgsConstructor
@Slf4j
public class WorkPlanController {

    private static final Long DEMO_SITE_ID = 1L;

    private final WorkPlanService workPlanService;

    /** 점검 기록 목록. keyword는 작업명 또는 설비명 부분 일치, status는 상태 하나 */
    @GetMapping
    public ResponseEntity<PageResponse<WorkPlanDtos.ListItem>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) WorkPlanStatus status) {
        return ResponseEntity.ok(PageResponse.from(
                workPlanService.list(DEMO_SITE_ID, keyword, status, PageRequests.of(page, size))));
    }

    @GetMapping("/{workPlanId}")
    public ResponseEntity<WorkPlanDtos.Detail> detail(@PathVariable Long workPlanId) {
        return ResponseEntity.ok(workPlanService.detail(workPlanId));
    }

    /** TBM 실시 확인. 승인 후에만 기록한다. 이 시각이 TBM 이행 증빙이 된다 */
    @PostMapping("/{workPlanId}/ack")
    public ResponseEntity<WorkPlanDtos.Detail> acknowledge(@PathVariable Long workPlanId) {
        log.info("[UC3] 브리핑 확인 workPlanId={}", workPlanId);
        return ResponseEntity.ok(workPlanService.acknowledgeBriefing(workPlanId));
    }

    /** 작업 보류. 잠정조치가 작업 금지라 승인할 수 없을 때 */
    @PostMapping("/{workPlanId}/hold")
    public ResponseEntity<WorkPlanDtos.Detail> hold(
            @PathVariable Long workPlanId,
            @RequestBody(required = false) WorkPlanDtos.HoldRequest request) {
        return ResponseEntity.ok(workPlanService.hold(workPlanId, request != null ? request.reason() : null));
    }

    /** 관리감독자 승인. 조건(잠정조치)을 달면 조건부 승인이 된다 */
    @PostMapping("/{workPlanId}/approve")
    public ResponseEntity<WorkPlanDtos.Detail> approve(
            @PathVariable Long workPlanId,
            @RequestBody(required = false) WorkPlanDtos.ApproveRequest request) {
        String approver = request != null ? request.approver() : null;
        String condition = request != null ? request.condition() : null;
        return ResponseEntity.ok(workPlanService.approve(workPlanId, approver, condition));
    }
}
