package io.saife.publicapi.repository;

import io.saife.core.domain.AccidentType;
import io.saife.publicapi.domain.PublicCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PublicCaseRepository extends JpaRepository<PublicCase, Long> {

    /**
     * 업종 필터를 먼저 걸고 발생형태로 좁힌다.
     * 제조업 사례를 찾는데 건설 사례를 올리면 근거로서 약하다.
     */
    @Query("select c from PublicCase c "
            + "where (:accidentType is null or c.accidentType = :accidentType) "
            + "and (:business is null or c.business = :business) "
            + "order by c.occurredOn desc")
    List<PublicCase> search(@Param("accidentType") AccidentType accidentType,
                            @Param("business") String business);

    /** 임베딩이 준비되기 전의 폴백 — keyword 부분일치 */
    @Query("select c from PublicCase c where lower(c.keyword) like lower(concat('%', :kw, '%')) "
            + "order by c.occurredOn desc")
    List<PublicCase> searchByKeyword(@Param("kw") String keyword);
}
