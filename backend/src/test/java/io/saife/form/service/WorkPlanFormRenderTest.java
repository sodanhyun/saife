package io.saife.form.service;

import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.repository.WorkPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 작업 전 점검 서식(round2 B-2, B-3 요청분): 빈 절 숨김, 기한 날짜, 참고 자료 출처명, 제목 자간 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkPlanFormRenderTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WorkPlanRepository workPlanRepository;

    @Test
    @DisplayName("현장 확인 항목이 없으면 그 절을 숨기고, 출력 시점 경과일과 링크 글자를 찍지 않는다")
    void hidesEmptyChecksAndTimeDependentText() throws Exception {
        WorkPlan plan = workPlanRepository.save(WorkPlan.builder().siteId(1L).equipmentId(4L)
                .workName("금형 교체").workDate(LocalDate.now()).build());

        String html = mvc.perform(get("/form/work-plan/" + plan.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).doesNotContain("확인 항목 없음").doesNotContain(">현장 확인<");
        assertThat(html).doesNotContainPattern("\\(\\d+일 경과\\)");
        assertThat(html).doesNotContain("법제처 원문").doesNotContain("공단 사례 원문");
        assertThat(html).contains("관리감독자 확인", "확인 시각");
    }

    @Test
    @DisplayName("작업 보류 상태면 확인 시각 칸을 '보류 전 승인'으로 쓴다")
    void holdLabelsApprovalTime() throws Exception {
        WorkPlan plan = workPlanRepository.save(WorkPlan.builder().siteId(1L).equipmentId(4L)
                .workName("금형 교체").workDate(LocalDate.now()).build());
        plan.approve("김철수", null);
        plan.hold();
        workPlanRepository.save(plan);

        String html = mvc.perform(get("/form/work-plan/" + plan.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("보류 전 승인", "작업 보류");
    }
}
