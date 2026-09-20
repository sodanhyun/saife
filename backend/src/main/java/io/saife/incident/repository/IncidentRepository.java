package io.saife.incident.repository;

import io.saife.incident.domain.Incident;
import io.saife.incident.domain.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Page<Incident> findBySiteIdOrderByOccurredAtDesc(Long siteId, Pageable pageable);

    /** 같은 설비의 과거 사고. 소환의 핵심 — "같은 설비에서 또 났다" */
    List<Incident> findByEquipmentIdOrderByOccurredAtDesc(Long equipmentId);

    /** 제출 기한이 살아 있는 건. 타이머 화면이 읽는다 */
    List<Incident> findBySiteIdAndReportStatusIn(Long siteId, List<ReportStatus> statuses);
}
