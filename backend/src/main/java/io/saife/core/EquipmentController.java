package io.saife.core;

import io.saife.core.domain.Equipment;
import io.saife.core.domain.WorkProcess;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.ProcessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 설비 대장 조회. UC4 타임라인의 진입점이고 시연에서 설비를 고르는 목록이다.
 *
 * <p>설비 생성 API는 두지 않는다. 등록은 대화 중 되묻기 슬롯이 완료될 때
 * 백엔드 부수효과로 일어난다 — 규칙: {@code .claude/rules/risk-domain.md}
 */
@RestController
@RequestMapping("/api/equipment")
@RequiredArgsConstructor
public class EquipmentController {

    private static final Long DEMO_SITE_ID = 1L;

    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;

    public record EquipmentItem(Long id, String name, String locationTag,
                                String processName, LocalDate introducedOn) {}

    @GetMapping
    public ResponseEntity<List<EquipmentItem>> list() {
        List<EquipmentItem> items = equipmentRepository.findBySiteId(DEMO_SITE_ID)
                .stream()
                .map(this::toItem)
                .toList();
        return ResponseEntity.ok(items);
    }

    private EquipmentItem toItem(Equipment e) {
        WorkProcess process = e.getProcessId() == null ? null
                : processRepository.findById(e.getProcessId()).orElse(null);
        return new EquipmentItem(e.getId(), e.getName(),
                process != null ? process.getLocationTag() : e.getLocationTag(),
                process != null ? process.getName() : null,
                e.getIntroducedOn());
    }
}
