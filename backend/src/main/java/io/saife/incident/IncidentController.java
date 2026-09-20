package io.saife.incident;

import io.saife.common.dto.PageResponse;
import io.saife.incident.dto.IncidentDtos;
import io.saife.incident.service.IncidentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * UC2 — 산업재해 등록과 콜백.
 *
 * <p>등록은 <b>한 번의 POST</b>다. 그 한 번으로 이력 소환·수시평가·법정 기한이
 * 모두 응답에 실려 돌아온다. 화면은 이 응답 하나로 그려진다 —
 * 추가 조회를 여러 번 하게 만들면 시연에서 로딩이 세 번 돈다.
 */
@RestController
@RequestMapping("/api/incident")
@RequiredArgsConstructor
@Slf4j
public class IncidentController {

    /** 가상 사업장 1곳. 테넌시가 없어 상수로 둔다 */
    private static final Long DEMO_SITE_ID = 1L;

    private final IncidentService incidentService;

    @PostMapping
    public ResponseEntity<IncidentDtos.RegisterResponse> register(
            @RequestBody IncidentDtos.RegisterRequest request) {
        log.info("[UC2] 사고 등록 요청 equipmentId={} query={} type={}",
                request.equipmentId(), request.equipmentQuery(), request.accidentType());
        return ResponseEntity.ok(incidentService.register(DEMO_SITE_ID, request));
    }

    @GetMapping("/{incidentId}")
    public ResponseEntity<IncidentDtos.RegisterResponse> detail(@PathVariable Long incidentId) {
        return ResponseEntity.ok(incidentService.detail(incidentId));
    }

    @GetMapping
    public ResponseEntity<PageResponse<IncidentDtos.IncidentListItem>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(PageResponse.from(incidentService.list(DEMO_SITE_ID, pageable)));
    }
}
