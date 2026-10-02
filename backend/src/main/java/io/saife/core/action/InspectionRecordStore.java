package io.saife.core.action;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 순회점검 기록 항목(V13) 읽기와 쓰기. 시행규칙 제37조의4 기록 항목 중 엔티티에 없는 것들이다.
 *
 * <ul>
 *   <li>{@code assessment.inspector}: 담당자(점검자)</li>
 *   <li>{@code assessment_hazard.acceptable}: 허용 가능 여부</li>
 *   <li>{@code action.priority}: 감소대책 우선순위({@link ControlPriority})</li>
 * </ul>
 *
 * <p>core/domain 엔티티는 공유 소유라 컬럼 매핑을 넣지 않고 여기서 JDBC로 다룬다.
 * {@code ddl-auto: validate}는 매핑되지 않은 컬럼을 문제 삼지 않는다. JPA 트랜잭션 안에서 부르면
 * 같은 커넥션을 쓰므로(JpaTransactionManager) 방금 저장한 행에도 바로 쓸 수 있다.
 */
@Component
@RequiredArgsConstructor
public class InspectionRecordStore {

    private final JdbcTemplate jdbc;

    /** 점검 정보. participants는 쉼표로 이은 이름 그대로다 */
    public record Inspection(String inspector, String participants) {}

    public void saveInspection(Long assessmentId, String inspector, String participants) {
        jdbc.update("UPDATE assessment SET inspector = ?, participants = ? WHERE id = ?",
                inspector, participants, assessmentId);
    }

    public Optional<Inspection> inspection(Long assessmentId) {
        List<Inspection> rows = jdbc.query(
                "SELECT inspector, participants FROM assessment WHERE id = ?",
                (rs, i) -> new Inspection(rs.getString("inspector"), rs.getString("participants")),
                assessmentId);
        return rows.stream().findFirst();
    }

    public void saveAcceptable(Long assessmentId, Long hazardId, Boolean acceptable) {
        jdbc.update("UPDATE assessment_hazard SET acceptable = ? WHERE assessment_id = ? AND hazard_id = ?",
                acceptable, assessmentId, hazardId);
    }

    /** 이 평가의 위험요인별 허용 가능 여부. 사람이 정하지 않은 것은 맵에 없다 */
    public Map<Long, Boolean> acceptableByHazard(Long assessmentId) {
        Map<Long, Boolean> out = new HashMap<>();
        jdbc.query("SELECT hazard_id, acceptable FROM assessment_hazard WHERE assessment_id = ? AND acceptable IS NOT NULL",
                rs -> {
                    out.put(rs.getLong("hazard_id"), rs.getBoolean("acceptable"));
                },
                assessmentId);
        return out;
    }

    public void savePriority(Long actionId, ControlPriority priority) {
        jdbc.update("UPDATE action SET priority = ? WHERE id = ?",
                priority == null ? null : priority.name(), actionId);
    }

    public ControlPriority priority(Long actionId) {
        if (actionId == null) {
            return null;
        }
        List<String> rows = jdbc.queryForList("SELECT priority FROM action WHERE id = ?", String.class, actionId);
        return rows.isEmpty() ? null : ControlPriority.parse(rows.get(0));
    }
}
