package io.saife.workplan.repository;

import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkPlanRepository extends JpaRepository<WorkPlan, Long> {

    Page<WorkPlan> findBySiteIdOrderByWorkDateDesc(Long siteId, Pageable pageable);

    List<WorkPlan> findByEquipmentIdOrderByWorkDateDesc(Long equipmentId);

    /**
     * 점검 기록 목록 검색. 작업명 또는 설비명 부분 일치(ILIKE), 상태 필터. 작업일 최신순.
     *
     * @param keyword  소문자 LIKE 패턴(예: "%인양%"). 검색어가 없으면 "%"
     * @param statuses 보여줄 상태. 필터가 없으면 전체 상태
     */
    @Query(value = """
            select w from WorkPlan w
            where w.siteId = :siteId
              and w.status in :statuses
              and (lower(w.workName) like :keyword
                   or exists (select 1 from Equipment e where e.id = w.equipmentId and lower(e.name) like :keyword))
            order by w.workDate desc, w.id desc
            """)
    Page<WorkPlan> search(@Param("siteId") Long siteId, @Param("keyword") String keyword,
                          @Param("statuses") Collection<WorkPlanStatus> statuses, Pageable pageable);

    List<WorkPlan> findBySiteIdAndStatus(Long siteId, WorkPlanStatus status);

    /**
     * 이 대화가 만든 초안을 찾는다. <b>한 대화 = 한 초안.</b>
     *
     * <p>되묻기 때문에 {@code extractWorkPlan}이 여러 번 호출되는 것이 <b>정상 흐름</b>이다.
     * 호출마다 새로 만들면 같은 작업의 계획서가 쪼개지고, 슬롯이 여기저기 흩어져
     * 브리핑이 반쪽만 나온다.
     *
     * <p>식별 키를 작업명으로 잡으면 안 된다 — 모델이 대화 중간에 작업명을 다듬으면
     * ("천장 페인트 작업" → "공장동 후면 차양부 천장 페인트 작업") 키가 바뀌어
     * 똑같이 쪼개진다. 실제로 그렇게 쪼개졌다(2026-09-20 스모크 테스트).
     * 대화 ID는 서버가 발급한 값이라 흔들리지 않는다.
     */
    Optional<WorkPlan> findFirstByConversationIdAndStatusOrderByIdDesc(
            String conversationId, WorkPlanStatus status);

    /** 대화 ID가 없는 경로(테스트·수동 호출)를 위한 차선책 키 */
    List<WorkPlan> findBySiteIdAndWorkNameAndWorkDateAndStatusOrderByIdDesc(
            Long siteId, String workName, LocalDate workDate, WorkPlanStatus status);

    /**
     * 사고 연쇄(UC2)가 경고를 붙일 대상 — 같은 설비 · 진행 중 상태(SUBMITTED/APPROVED/CONDITIONAL)
     * · 작업일이 사고일 이후인 작업계획서만. 사고일 이전에 끝난 작업까지 경고하면 의미가 없다.
     */
    List<WorkPlan> findBySiteIdAndEquipmentIdAndStatusInAndWorkDateGreaterThanEqualOrderByWorkDateAsc(
            Long siteId, Long equipmentId, List<WorkPlanStatus> statuses, LocalDate workDate);
}
