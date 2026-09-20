package io.saife.core.repository;

import io.saife.core.domain.Hazard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HazardRepository extends JpaRepository<Hazard, Long> {

    List<Hazard> findByEquipmentIdOrderByCreatedAtDesc(Long equipmentId);

    List<Hazard> findByProcessIdOrderByCreatedAtDesc(Long processId);

    /** 후보 채택률 지표의 분모 */
    @Query("select count(h) from Hazard h where h.siteId = :siteId and h.aiSuggested = true")
    long countAiSuggested(@Param("siteId") Long siteId);

    /** 후보 채택률 지표의 분자 */
    @Query("select count(h) from Hazard h where h.siteId = :siteId "
            + "and h.aiSuggested = true and h.aiAdopted = true")
    long countAiAdopted(@Param("siteId") Long siteId);
}
