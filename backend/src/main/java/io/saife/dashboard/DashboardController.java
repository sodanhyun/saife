package io.saife.dashboard;

import io.saife.dashboard.dto.TimelineDtos;
import io.saife.dashboard.service.EquipmentTimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * UC4 — 설비 타임라인. 읽기 전용이다.
 *
 * <p>여기서 상태를 바꾸지 않는다. 시연 중 조회 때문에 데이터가 변하면
 * 같은 화면을 두 번 보여줄 수 없다.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final EquipmentTimelineService equipmentTimelineService;

    @GetMapping("/equipment/{equipmentId}/timeline")
    public ResponseEntity<TimelineDtos.EquipmentTimeline> timeline(@PathVariable Long equipmentId) {
        return ResponseEntity.ok(equipmentTimelineService.timeline(equipmentId));
    }
}
