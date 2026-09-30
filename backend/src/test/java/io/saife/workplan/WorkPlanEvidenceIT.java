package io.saife.workplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.WorkPlanEvidence;
import io.saife.evidence.live.Origin;
import io.saife.evidence.repository.WorkPlanEvidenceRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.service.WorkPlanService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code work_plan_evidence}에 저장된 근거가 상세 응답에 번호순으로 붙는지 검증한다.
 *
 * <p>실제 저장 흐름({@code WorkPlanTools.createWorkPlan})은 대화 원장·에이전트 컨텍스트가
 * 필요해 여기서는 리포지토리에 직접 근거를 심고 {@link WorkPlanService#detail(Long)}만 검증한다.
 * 5433 DB(실 시드)에 대해 돌지만 {@code @Transactional}이라 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkPlanEvidenceIT {
    @Autowired WorkPlanRepository plans;
    @Autowired WorkPlanEvidenceRepository evidences;
    @Autowired WorkPlanService service;
    @Autowired ObjectMapper om;

    @Test
    void 상세_응답에_근거가_번호순으로_붙는다() throws Exception {
        WorkPlan p = plans.save(WorkPlan.builder().siteId(1L).workName("IT-테스트").workDate(LocalDate.now()).build());
        Evidence e2 = new Evidence(2, EvidenceKind.LAW, 1L, "IT-L:36:0", "산업안전보건법 제36조", "s", "https://law", null, null, Origin.CACHE, 1, OffsetDateTime.now(), Map.of());
        Evidence e1 = new Evidence(1, EvidenceKind.CASE_FATALITY, 1L, "IT-F:1", "사례", "s", null, "/api/media/case/1/photo", null, Origin.CACHE, 0.8, OffsetDateTime.now(), Map.of());
        evidences.save(WorkPlanEvidence.builder().workPlanId(p.getId()).evidenceNo(2).payload(om.writeValueAsString(e2)).build());
        evidences.save(WorkPlanEvidence.builder().workPlanId(p.getId()).evidenceNo(1).payload(om.writeValueAsString(e1)).build());
        var detail = service.detail(p.getId());
        assertThat(detail.evidence()).extracting(Evidence::no).containsExactly(1, 2);
        assertThat(detail.evidence().get(0).mediaUrl()).isEqualTo("/api/media/case/1/photo");
    }
}
