package io.saife.core.repository;

import io.saife.core.domain.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    List<Equipment> findBySiteId(Long siteId);

    /**
     * 1차 완전일치 — 유니크 제약과 같은 규칙(사업장 + 위치 + 정규화 명칭).
     * 여기서 잡히면 절대 새로 만들지 않는다.
     */
    @Query("select e from Equipment e where e.siteId = :siteId "
            + "and coalesce(e.locationTag, '') = coalesce(:locationTag, '') "
            + "and e.normalizedName = :normalizedName")
    Optional<Equipment> findExact(@Param("siteId") Long siteId,
                                  @Param("locationTag") String locationTag,
                                  @Param("normalizedName") String normalizedName);

    /** 2차 후보 — 유사도 계산 대상을 좁히기 위한 넓은 조회 */
    @Query("select e from Equipment e where e.siteId = :siteId "
            + "and (lower(e.name) like lower(concat('%', :kw, '%')) "
            + "or lower(e.locationTag) like lower(concat('%', :kw, '%')))")
    List<Equipment> searchByKeyword(@Param("siteId") Long siteId, @Param("kw") String keyword);
}
