package io.saife.core.repository;

import io.saife.core.domain.AssessmentHazard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssessmentHazardRepository extends JpaRepository<AssessmentHazard, Long> {

    List<AssessmentHazard> findByAssessmentId(Long assessmentId);

    List<AssessmentHazard> findByHazardId(Long hazardId);

    /**
     * 위험요인의 최근 등급 이력 — UC3 브리핑이
     * "3개월 전 평가에서 '상'이었습니다"를 말하는 근거.
     * 호출부에서 첫 건만 쓴다.
     */
    @Query("select ah from AssessmentHazard ah, Assessment a "
            + "where a.id = ah.assessmentId and ah.hazardId = :hazardId "
            + "order by a.assessedOn desc")
    List<AssessmentHazard> findHistoryByHazardId(@Param("hazardId") Long hazardId);
}
