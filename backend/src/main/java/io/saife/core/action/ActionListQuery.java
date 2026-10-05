package io.saife.core.action;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.ActionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 개선대책 목록 조회(DB 측 필터, 정렬, 페이징).
 *
 * <p>{@code action.priority}(V13)는 엔티티에 매핑되지 않은 컬럼이라({@link InspectionRecordStore} 참고)
 * JPQL 대신 네이티브 SQL로 위험요인, 설비를 조인해 한 번에 읽는다.
 */
@Component
@RequiredArgsConstructor
public class ActionListQuery {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 기한 경과: OVERDUE로 표시됐거나, 아직 끝나지 않았는데 기한이 오늘보다 앞이다 */
    private static final String OVERDUE_COND =
            "(a.status <> 'DONE' AND (a.status = 'OVERDUE' OR a.due_date < :today))";

    private static final String FROM = """
            FROM action a
            LEFT JOIN hazard h ON h.id = a.hazard_id
            LEFT JOIN equipment e ON e.id = h.equipment_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** 목록 한 쪽과 전체 건수 */
    public record Slice(List<ActionDtos.ActionListItem> content, long total) {}

    public Slice find(ActionListFilter filter, String keyword, int page, int size, LocalDate today) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("today", Date.valueOf(today))
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        String where = where(filter, keyword, p);

        Long total = jdbc.queryForObject("SELECT count(*) " + FROM + where, p, Long.class);

        String sql = """
                SELECT a.id, a.content, a.owner, a.due_date, a.status, a.completed_at, a.priority,
                       a.guide_ref, a.hazard_id, a.assessment_id, a.verified_by, a.residual_level, a.evidence_path,
                       (SELECT count(*) FROM action b WHERE b.hazard_id = a.hazard_id AND b.id <> a.id
                           AND b.status <> 'DONE') AS related_open,
                       h.accident_type, h.missing_control, h.equipment_id, e.name AS equipment_name,
                       %s AS overdue
                """.formatted(OVERDUE_COND)
                + FROM + where + " ORDER BY " + orderBy(filter) + " LIMIT :limit OFFSET :offset";

        List<ActionDtos.ActionListItem> rows = jdbc.query(sql, p, mapper(today));
        return new Slice(rows, total == null ? 0 : total);
    }

    public ActionDtos.ActionCounts counts(LocalDate today) {
        MapSqlParameterSource p = new MapSqlParameterSource("today", Date.valueOf(today));
        String sql = """
                SELECT count(*) FILTER (WHERE a.status <> 'DONE') AS open,
                       count(*) FILTER (WHERE %s) AS overdue,
                       count(*) FILTER (WHERE a.status = 'DONE') AS done
                FROM action a
                """.formatted(OVERDUE_COND);
        return jdbc.queryForObject(sql, p, (rs, i) -> new ActionDtos.ActionCounts(
                rs.getLong("open"), rs.getLong("overdue"), rs.getLong("done")));
    }

    private String where(ActionListFilter filter, String keyword, MapSqlParameterSource p) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        switch (filter) {
            case OPEN -> w.append(" AND a.status <> 'DONE'");
            case OVERDUE -> w.append(" AND ").append(OVERDUE_COND);
            case DONE -> w.append(" AND a.status = 'DONE'");
            case ALL -> { }
        }
        if (keyword != null && !keyword.isBlank()) {
            p.addValue("kw", "%" + escapeLike(keyword.strip()) + "%");
            w.append(" AND (a.content ILIKE :kw ESCAPE '\\' OR a.owner ILIKE :kw ESCAPE '\\'")
                    .append(" OR e.name ILIKE :kw ESCAPE '\\')");
        }
        return w.toString();
    }

    private String orderBy(ActionListFilter filter) {
        return switch (filter) {
            case OPEN, OVERDUE -> "a.due_date ASC NULLS LAST, a.id ASC";
            case DONE -> "a.completed_at DESC NULLS LAST, a.id DESC";
            case ALL -> "CASE WHEN a.status <> 'DONE' THEN 0 ELSE 1 END, a.due_date ASC NULLS LAST, a.id ASC";
        };
    }

    private RowMapper<ActionDtos.ActionListItem> mapper(LocalDate today) {
        return (rs, i) -> {
            LocalDate due = toLocalDate(rs.getDate("due_date"));
            boolean overdue = rs.getBoolean("overdue");
            ActionStatus stored = ActionStatus.valueOf(rs.getString("status"));
            // 화면 상태는 판정 결과를 쓴다(저장값이 PENDING이어도 기한이 지났으면 OVERDUE)
            ActionStatus status = overdue ? ActionStatus.OVERDUE
                    : stored == ActionStatus.OVERDUE ? ActionStatus.PENDING : stored;
            Long overdueDays = overdue && due != null
                    ? Math.max(0L, ChronoUnit.DAYS.between(due, today))
                    : null;
            return new ActionDtos.ActionListItem(
                    rs.getLong("id"),
                    rs.getString("content"),
                    rs.getString("owner"),
                    due,
                    status,
                    overdueDays,
                    toOffset(rs.getTimestamp("completed_at")),
                    ControlPriority.parse(rs.getString("priority")),
                    rs.getString("guide_ref"),
                    getLong(rs, "hazard_id"),
                    parseAccident(rs.getString("accident_type")),
                    rs.getString("missing_control"),
                    getLong(rs, "equipment_id"),
                    rs.getString("equipment_name"),
                    getLong(rs, "assessment_id"),
                    rs.getString("verified_by"),
                    parseLevel(rs.getString("residual_level")),
                    ActionDtos.evidenceUrl(rs.getLong("id"), rs.getString("evidence_path")),
                    rs.getInt("related_open"));
        };
    }

    private static io.saife.core.domain.RiskLevel parseLevel(String raw) {
        if (raw == null) return null;
        try {
            return io.saife.core.domain.RiskLevel.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDate toLocalDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static OffsetDateTime toOffset(Timestamp t) {
        return t == null ? null : t.toInstant().atZone(KST).toOffsetDateTime();
    }

    private static AccidentType parseAccident(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return AccidentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
