package io.saife.publicapi.repository;

import io.saife.publicapi.domain.CrawlCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlCheckpointRepository extends JpaRepository<CrawlCheckpoint, String> {
}
