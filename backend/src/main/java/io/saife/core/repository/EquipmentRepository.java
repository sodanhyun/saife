package io.saife.core.repository;

import io.saife.core.domain.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    /**
     * 설비 목록.
     *
     * <p><b>정렬을 반드시 건다.</b> ORDER BY 없는 조회는 Postgres가 순서를 보장하지 않고,
     * 실제로 id 순도 이름 순도 아닌 순서로 돌아왔다 (2026-09-21 실측: 2,1,5,3,4,6).
     * 화면의 기본 선택이 그 첫 번째 행이라, 무대에서 드롭다운이 다른 설비를 물고 시작하면
     * 시연 대본이 어긋난다.
     */
    List<Equipment> findBySiteIdOrderByIdAsc(Long siteId);


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
