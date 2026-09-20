package io.saife.workplan.repository;

import io.saife.workplan.domain.WorkPlanWorker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkPlanWorkerRepository extends JpaRepository<WorkPlanWorker, Long> {
    List<WorkPlanWorker> findByWorkPlanId(Long workPlanId);
}
