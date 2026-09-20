package io.saife.workplan.repository;

import io.saife.workplan.domain.WorkPlanSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkPlanSlotRepository extends JpaRepository<WorkPlanSlot, Long> {
    List<WorkPlanSlot> findByWorkPlanId(Long workPlanId);
    Optional<WorkPlanSlot> findByWorkPlanIdAndSlotKey(Long workPlanId, String slotKey);
}
