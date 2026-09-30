package io.saife.publicapi.repository;

import io.saife.publicapi.domain.MsdsCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MsdsCacheRepository extends JpaRepository<MsdsCache, Long> {

    /** 제품명 부분일치로 물질을 찾는다 */
    @Query("select distinct m.chemId from MsdsCache m "
            + "where lower(m.chemNameKor) like lower(concat('%', :kw, '%'))")
    List<String> findChemIdsByName(@Param("kw") String keyword);

    List<MsdsCache> findByChemIdOrderBySectionCode(String chemId);

    List<MsdsCache> findByChemIdAndSectionCodeIn(String chemId, List<String> sectionCodes);

    List<MsdsCache> findByChemId(String chemId);
}
