package io.saife.core.repository;

import io.saife.core.domain.WorkProcess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProcessRepository extends JpaRepository<WorkProcess, Long> {

    List<WorkProcess> findBySiteId(Long siteId);

    /** 위치 태그·명칭 부분일치. 자연어 매칭의 1차 후보 */
    @Query("select p from WorkProcess p where p.siteId = :siteId "
            + "and (lower(p.locationTag) like lower(concat('%', :kw, '%')) "
            + "or lower(p.name) like lower(concat('%', :kw, '%')))")
    List<WorkProcess> searchByKeyword(@Param("siteId") Long siteId, @Param("kw") String keyword);
}
