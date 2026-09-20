package io.saife.core.repository;

import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ActionRepository extends JpaRepository<Action, Long> {

    List<Action> findByHazardId(Long hazardId);

    /** 미이행 조치 — UC3 브리핑에서 경고로 소환된다 */
    @Query("select a from Action a where a.hazardId in :hazardIds and a.status <> :done")
    List<Action> findPendingByHazardIds(@Param("hazardIds") List<Long> hazardIds,
                                        @Param("done") ActionStatus done);
}
