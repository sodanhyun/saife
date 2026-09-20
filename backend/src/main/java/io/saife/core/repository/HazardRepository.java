package io.saife.core.repository;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Hazard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HazardRepository extends JpaRepository<Hazard, Long> {

    List<Hazard> findByEquipmentIdOrderByCreatedAtDesc(Long equipmentId);

    List<Hazard> findByProcessIdOrderByCreatedAtDesc(Long processId);

    /**
     * 채택률 지표 — <b>게이트를 통과한 축만 센다.</b>
     *
     * <p>사진 판독 검증(2026-09-21)을 통과하지 못한 축의 후보를 지표에 넣으면,
     * 게이트 결과를 본 심사위원이 "검증 안 된 축을 성과에 넣었다"고 짚는다.
     * 축 목록은 {@code GateStatus}가 갖고 있다.
     */
    @Query("select count(h) from Hazard h where h.siteId = :siteId "
            + "and h.aiSuggested = true and h.accidentType in :axes")
    long countAiSuggestedIn(@Param("siteId") Long siteId,
                            @Param("axes") List<AccidentType> axes);

    @Query("select count(h) from Hazard h where h.siteId = :siteId "
            + "and h.aiSuggested = true and h.aiAdopted = true and h.accidentType in :axes")
    long countAiAdoptedIn(@Param("siteId") Long siteId,
                          @Param("axes") List<AccidentType> axes);

    /** 후보 채택률 지표의 분모 */
    @Query("select count(h) from Hazard h where h.siteId = :siteId and h.aiSuggested = true")
    long countAiSuggested(@Param("siteId") Long siteId);

    /** 후보 채택률 지표의 분자 */
    @Query("select count(h) from Hazard h where h.siteId = :siteId "
            + "and h.aiSuggested = true and h.aiAdopted = true")
    long countAiAdopted(@Param("siteId") Long siteId);
}
