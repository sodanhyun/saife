package io.saife.publicapi.repository;

import io.saife.publicapi.domain.KoshaGuide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KoshaGuideRepository extends JpaRepository<KoshaGuide, Long> {

    Optional<KoshaGuide> findByGuideNo(String guideNo);

    @Query("select g from KoshaGuide g where lower(g.guideName) like lower(concat('%', :kw, '%'))")
    List<KoshaGuide> searchByName(@Param("kw") String keyword);
}
