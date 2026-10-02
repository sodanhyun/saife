package io.saife.incident.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 수시평가 확정 시각({@code assessment.confirmed_at}, V16). 평가 엔티티는 공유 소유라 컬럼 매핑을 넣지 않고
 * 여기서 JDBC로 다룬다({@code InspectionRecordStore}와 같은 방식). JPA 트랜잭션 안에서 부르면 같은 커넥션을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class FollowUpRecordStore {

    private final JdbcTemplate jdbc;

    public void saveConfirmedAt(Long assessmentId, OffsetDateTime at) {
        jdbc.update("UPDATE assessment SET confirmed_at = ? WHERE id = ?",
                at == null ? null : Timestamp.from(at.toInstant()), assessmentId);
    }

    public Optional<OffsetDateTime> confirmedAt(Long assessmentId) {
        if (assessmentId == null) {
            return Optional.empty();
        }
        List<Timestamp> rows = jdbc.queryForList(
                "SELECT confirmed_at FROM assessment WHERE id = ?", Timestamp.class, assessmentId);
        return rows.stream().filter(t -> t != null).findFirst()
                .map(t -> t.toInstant().atOffset(ZoneOffset.UTC));
    }
}
