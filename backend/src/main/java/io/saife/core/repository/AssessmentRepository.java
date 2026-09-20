package io.saife.core.repository;

import io.saife.core.domain.Assessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssessmentRepository extends JpaRepository<Assessment, Long> {
    List<Assessment> findBySiteIdOrderByAssessedOnDesc(Long siteId);
}
