package io.saife.form.service;

import io.saife.core.domain.AccidentType;
import io.saife.form.dto.IncidentFormViews;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.dto.IncidentDtos;
import io.saife.incident.service.IncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사고 서식 두 종: 제출용 산업재해조사표(별지 제30호서식 항목)와 사내 재발방지 검토서.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IncidentFormServiceTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private MockMvc mvc;

    private Long registerLadderFall() {
        IncidentDtos.RegisterRequest req = new IncidentDtos.RegisterRequest(1L, null, null, OffsetDateTime.now(),
                null, IncidentSeverity.LOST_TIME, 5, AccidentType.FALL,
                "차양부 천장 도장 중 이동식 사다리 최상부 바로 아래 디딤대에서 중심을 잃고 약 2.5m 아래로 떨어짐");
        return incidentService.register(1L, req).incident().id();
    }

    @Test
    @DisplayName("재발방지 줄을 무엇을, 누가, 언제까지로 가르고 근거 번호는 따로 뽑는다")
    void planRowsParseWhoAndWhen() {
        List<IncidentFormViews.PlanRow> rows = IncidentFormService.planRows("""
                1. 차양부 천장 작업 시 이동식 비계(안전난간) 사용 (담당 생산반장 김철수, 기한 작업 재개 전) [#4]
                2. 수시평가 완료 후 작업 재개 (담당 안전관리자, 기한 2026-10-09)
                작업 전 TBM에서 사다리 사용 기준 공유
                """);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).what()).isEqualTo("차양부 천장 작업 시 이동식 비계(안전난간) 사용");
        assertThat(rows.get(0).who()).isEqualTo("생산반장 김철수");
        assertThat(rows.get(0).when()).isEqualTo("작업 재개 전");
        assertThat(rows.get(0).refs()).containsExactly(4);
        assertThat(rows.get(1).when()).isEqualTo("2026-10-09");
        assertThat(rows.get(2).no()).isEqualTo(3);
        assertThat(rows.get(2).what()).isEqualTo("작업 전 TBM에서 사다리 사용 기준 공유");
        assertThat(rows.get(2).who()).isEmpty();
    }

    @Test
    @DisplayName("근거 번호는 조사표 원인에서 지운다")
    void stripCitations() {
        assertThat(IncidentFormService.stripCitations("떨어짐 [#1][#2].")).isEqualTo("떨어짐.");
    }

    @Test
    @DisplayName("산업재해조사표는 별지 제30호서식 항목을 갖추고, 사람 정보는 빈칸이며, 사내 검토 내용은 담지 않는다")
    void reportFormRendersOfficialItems() throws Exception {
        Long id = registerLadderFall();

        String html = mvc.perform(get("/form/incident/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("별지 제30호서식", "산재관리번호", "사업자등록번호", "근로자 수", "업종", "소재지",
                "주민등록번호", "국적", "체류자격", "같은 종류 업무 근속기간", "고용형태", "근무형태",
                "상해종류", "상해부위", "휴업예상일수", "사망 여부", "재해관련 작업유형", "재해발생 당시 상황",
                "재해발생 원인", "재발방지 계획", "근로자대표", "이동식 사다리 A");
        assertThat(html).doesNotContain("예고", "사고 전 이 설비에", "미이행");
    }

    @Test
    @DisplayName("재발방지 검토서는 사고 전 지적 사항 이행 현황, 재발방지 대책, 수시평가 연결을 담는다")
    void reviewFormRendersPriorFindings() throws Exception {
        Long id = registerLadderFall();

        String html = mvc.perform(get("/form/incident/" + id + "/review"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("재 발 방 지 검 토 서", "제출하지 않음", "사고 전 지적 사항 이행 현황",
                "재발방지 대책", "수시평가 연결", "시행규칙 제37조제2항제3호", "일 경과");
    }
}
