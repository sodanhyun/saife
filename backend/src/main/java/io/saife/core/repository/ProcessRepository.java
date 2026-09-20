package io.saife.core.repository;

import io.saife.core.domain.WorkProcess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProcessRepository extends JpaRepository<WorkProcess, Long> {

    /** 정렬을 건다. 장소 태그 매칭이 순서에 따라 달라지면 안 된다 */
    List<WorkProcess> findBySiteIdOrderByIdAsc(Long siteId);

    /** 위치 태그·명칭 부분일치. 자연어 매칭의 1차 후보 */
    @Query("select p from WorkProcess p where p.siteId = :siteId "
            + "and (lower(p.locationTag) like lower(concat('%', :kw, '%')) "
            + "or lower(p.name) like lower(concat('%', :kw, '%')))")
    List<WorkProcess> searchByKeyword(@Param("siteId") Long siteId, @Param("kw") String keyword);
}
