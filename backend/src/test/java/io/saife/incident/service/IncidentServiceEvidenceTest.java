package io.saife.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.core.domain.AccidentType;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.service.EquipmentMatcher;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.evidence.service.LawArticleService;
import io.saife.evidence.live.Fetched;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.dto.IncidentDtos;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.repository.WorkPlanRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class IncidentServiceEvidenceTest {
    @Test
    void 검색_실패는_등록을_막지_않는다() {
        EvidenceSearchService search = mock(EvidenceSearchService.class);
        when(search.search(any())).thenThrow(new RuntimeException("db down"));
        LawArticleService laws = mock(LawArticleService.class);
        when(laws.get(anyString(), anyInt(), anyInt())).thenReturn(Fetched.empty("x"));
        IncidentEvidenceCollector collector = new IncidentEvidenceCollector(search, laws);
        var out = collector.collect("스크류에 끼임", io.saife.core.domain.AccidentType.CAUGHT);
        assertThat(out.similarCases()).isEmpty();
        assertThat(out.all()).isEmpty();
    }

    @Test
    void 번호는_1부터_유일하게() {
        EvidenceSearchService search = mock(EvidenceSearchService.class);
        io.saife.evidence.Evidence c = new io.saife.evidence.Evidence(0, io.saife.evidence.EvidenceKind.CASE_FATALITY, 1L, "F:1", "사례", "s", null, null, null, io.saife.evidence.live.Origin.CACHE, 0.7, java.time.OffsetDateTime.now(), java.util.Map.of());
        when(search.search(any())).thenReturn(List.of(c));
        LawArticleService laws = mock(LawArticleService.class);
        io.saife.evidence.domain.LawArticle a = io.saife.evidence.domain.LawArticle.builder().id(3L).lawId("L").lawName("산업안전보건법 시행규칙").articleNo(73).articleSub(0).paragraphNo(1).title("산업재해 발생 보고").text("① …").build();
        when(laws.get(anyString(), anyInt(), anyInt())).thenReturn(new Fetched<>(List.of(a), io.saife.evidence.live.Origin.CACHE, java.time.OffsetDateTime.now(), null));
        var out = new IncidentEvidenceCollector(search, laws).collect("끼임", io.saife.core.domain.AccidentType.CAUGHT);
        assertThat(out.all()).extracting(io.saife.evidence.Evidence::no).containsExactly(1, 2, 3);
        assertThat(out.similarCases()).hasSize(1);
    }

    /** R54 IMPORTANT — accidentType은 등록 요청의 선택 필드다. 축이 없다고 유사 사례 근거 자체가 사라지면 안 된다. */
    @Test
    void 발생형태가_없어도_유사_사례_검색은_그대로_돈다() {
        EvidenceSearchService search = mock(EvidenceSearchService.class);
        io.saife.evidence.Evidence c = new io.saife.evidence.Evidence(0, io.saife.evidence.EvidenceKind.CASE_DISASTER,
                2L, "D:2", "사례", "s", null, null, null, io.saife.evidence.live.Origin.CACHE, 0.6,
                java.time.OffsetDateTime.now(), java.util.Map.of());
        when(search.search(any())).thenReturn(List.of(c));
        LawArticleService laws = mock(LawArticleService.class);
        when(laws.get(anyString(), anyInt(), anyInt())).thenReturn(Fetched.empty("x"));

        var out = new IncidentEvidenceCollector(search, laws).collect("컨베이어에 끼임", null);

        assertThat(out.similarCases()).hasSize(1);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(captor.capture());
        // 축 필터·가산점 없이(accidentType=null) 설명 키워드 그대로 검색한다 — 위치 태그를 붙이지 않는다
        assertThat(captor.getValue().accidentType()).isNull();
        assertThat(captor.getValue().query()).isEqualTo("컨베이어에 끼임");
    }

    /**
     * R54 CRITICAL — {@code attachDraft}가 던져도(예: DB 순간 장애) 이미 커밋된 등록(사고·수시평가)을
     * 되돌릴 수 없다. 클라이언트는 500 대신 정상 응답을 받아야 하고, 재시도로 사고가 중복 생성돼선 안 된다.
     */
    @SuppressWarnings("unchecked")
    @Test
    void attachDraft가_실패해도_등록_응답은_그대로_반환된다() {
        IncidentRepository incidentRepository = mock(IncidentRepository.class);
        EquipmentRepository equipmentRepository = mock(EquipmentRepository.class);
        EquipmentMatcher equipmentMatcher = mock(EquipmentMatcher.class);
        EquipmentHistoryRecaller recaller = mock(EquipmentHistoryRecaller.class);
        FollowUpAssessmentService followUpAssessmentService = mock(FollowUpAssessmentService.class);
        IncidentReportDrafter drafter = mock(IncidentReportDrafter.class);
        IncidentEvidenceCollector evidenceCollector = mock(IncidentEvidenceCollector.class);
        WorkPlanRepository workPlanRepository = mock(WorkPlanRepository.class);
        ObjectProvider<IncidentService> self = mock(ObjectProvider.class);

        IncidentService plain = new IncidentService(incidentRepository, equipmentRepository, equipmentMatcher,
                recaller, followUpAssessmentService, drafter, evidenceCollector, workPlanRepository,
                mock(io.saife.core.repository.ProcessRepository.class), self);
        IncidentService service = spy(plain);
        when(self.getObject()).thenReturn(service);

        Incident committed = Incident.builder()
                .id(42L).siteId(1L).equipmentId(4L)
                .occurredAt(OffsetDateTime.now())
                .victimName("홍OO").severity(IncidentSeverity.LOST_TIME).leaveDays(5)
                .accidentType(AccidentType.CAUGHT).description("스크류에 끼임")
                .build();

        EquipmentHistoryRecaller.Recall recall = new EquipmentHistoryRecaller.Recall(4L, "유압 프레스 3호", null,
                List.of(), List.of(), List.of(), List.of(), false, null, "사고 전 같은 발생형태의 위험요인 기록 없음");
        TimelineDtos.RecallView recallView = TimelineDtos.RecallView.from(recall);
        IncidentDtos.ReportDuty reportDuty = new IncidentDtos.ReportDuty(
                ReportStatus.REQUIRED, "제출 필요", LocalDate.now().plusMonths(1), 30L, "근거", false);
        IncidentDtos.FollowUpView followUpView = new IncidentDtos.FollowUpView(9L, "수시", "근거", List.of(), null);
        IncidentService.RegisterCore core = new IncidentService.RegisterCore(
                committed, recall, recallView, reportDuty, followUpView, List.of());

        IncidentDtos.RegisterRequest request = new IncidentDtos.RegisterRequest(4L, null, null,
                committed.getOccurredAt(), "홍OO", IncidentSeverity.LOST_TIME, 5, AccidentType.CAUGHT, "스크류에 끼임");

        doReturn(core).when(service).registerTransactional(eq(1L), eq(request));
        when(evidenceCollector.collect(anyString(), any()))
                .thenReturn(new IncidentEvidenceCollector.Collected(List.of(), List.of()));
        when(drafter.draft(any(), any(), anyList()))
                .thenReturn(new IncidentReportDrafter.Draft("원인 텍스트", "재발방지 텍스트", true));
        doThrow(new RuntimeException("db blip")).when(service).attachDraft(eq(42L), any());

        IncidentDtos.RegisterResponse response = service.register(1L, request);

        assertThat(response.incident().id()).isEqualTo(42L);
        assertThat(response.draft().cause()).isEqualTo("원인 텍스트");
        assertThat(response.draft().prevention()).isEqualTo("재발방지 텍스트");
        verify(service, times(1)).registerTransactional(eq(1L), eq(request));
        verify(service, times(1)).attachDraft(eq(42L), any());
    }
}
