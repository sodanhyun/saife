package io.saife.workplan.repository;

import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkPlanRepository extends JpaRepository<WorkPlan, Long> {
    Page<WorkPlan> findBySiteIdOrderByWorkDateDesc(Long siteId, Pageable pageable);
    List<WorkPlan> findByEquipmentIdOrderByWorkDateDesc(Long equipmentId);
    List<WorkPlan> findBySiteIdAndStatus(Long siteId, WorkPlanStatus status);
}
