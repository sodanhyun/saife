package io.saife.dashboard;

import io.saife.dashboard.dto.TimelineDtos;
import io.saife.dashboard.dto.TodayDtos;
import io.saife.dashboard.service.EquipmentTimelineService;
import io.saife.dashboard.service.TodayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * UC4 — 설비 타임라인, 설비 홈 카드, 설비 회상, 오늘 할 일. 전부 읽기 전용이다.
 *
 * <p>여기서 상태를 바꾸지 않는다. 시연 중 조회 때문에 데이터가 변하면
 * 같은 화면을 두 번 보여줄 수 없다.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    /** 가상 사업장 1곳. 테넌시가 없어 상수로 둔다 */
    private static final Long DEMO_SITE_ID = 1L;

    private final EquipmentTimelineService equipmentTimelineService;
    private final TodayService todayService;

    @GetMapping("/equipment/{equipmentId}/timeline")
    public ResponseEntity<TimelineDtos.EquipmentTimeline> timeline(@PathVariable Long equipmentId) {
        return ResponseEntity.ok(equipmentTimelineService.timeline(equipmentId));
    }

    /** 설비 홈 — IA를 뒤집는 화면이 읽는 목록. 설비 하나 위에 등급·미이행·사고가 한 장으로 */
    @GetMapping("/equipment/cards")
    public ResponseEntity<List<TimelineDtos.EquipmentCard>> cards() {
        return ResponseEntity.ok(equipmentTimelineService.cards(DEMO_SITE_ID));
    }

    /** 설비 회상 — 작업 신고 진입 시 "묻기 전에 먼저 말한다"의 근거 */
    @GetMapping("/equipment/{equipmentId}/recall")
    public ResponseEntity<TimelineDtos.RecallView> recall(@PathVariable Long equipmentId) {
        return ResponseEntity.ok(equipmentTimelineService.recall(equipmentId));
    }

    /** 오늘 할 일 — 기억이 만든 인박스. 7종 규칙을 합쳐 정렬·상한(20건) 적용 결과를 낸다 */
    @GetMapping("/today")
    public ResponseEntity<TodayDtos.TodayView> today() {
        return ResponseEntity.ok(todayService.today());
    }
}
