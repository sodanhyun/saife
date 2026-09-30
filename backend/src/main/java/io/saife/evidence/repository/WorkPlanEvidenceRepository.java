package io.saife.evidence.repository;
import io.saife.evidence.domain.WorkPlanEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface WorkPlanEvidenceRepository extends JpaRepository<WorkPlanEvidence, WorkPlanEvidence.Key> {
    List<WorkPlanEvidence> findByWorkPlanIdOrderByEvidenceNo(Long workPlanId);
}
