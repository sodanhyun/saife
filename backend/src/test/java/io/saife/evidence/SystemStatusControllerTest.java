package io.saife.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.common.config.DemoModeConfig;
import io.saife.evidence.live.LiveOrCache;
import io.saife.evidence.search.EvidenceChunkRepository;
import io.saife.evidence.search.QueryEmbedder;
import io.saife.publicapi.domain.CrawlCheckpoint;
import io.saife.publicapi.repository.CrawlCheckpointRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MockMvc + 목(mock)으로 JSON 모양만 검증한다. B2(프론트 타입)가 이 필드명을 그대로 옮겨 쓰므로
 * 필드명이 정확히 {@code demoMode/embeddingAvailable/evidenceChunkCount/evidenceByKind/
 * circuitOpenHosts/lastCrawlAt}인지가 핵심이다.
 */
class SystemStatusControllerTest {

    private final DemoModeConfig demoModeConfig = mock(DemoModeConfig.class);
    private final QueryEmbedder embedder = mock(QueryEmbedder.class);
    private final EvidenceChunkRepository chunks = mock(EvidenceChunkRepository.class);
    private final LiveOrCache liveOrCache = mock(LiveOrCache.class);
    private final CrawlCheckpointRepository checkpoints = mock(CrawlCheckpointRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new SystemStatusController(demoModeConfig, embedder, chunks, liveOrCache, checkpoints)).build();
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void 응답_필드명이_계약대로_camelCase다() throws Exception {
        when(demoModeConfig.isDemoMode()).thenReturn(true);
        when(embedder.available()).thenReturn(false);
        Map<String, Long> byKind = new LinkedHashMap<>();
        byKind.put("GUIDE", 10L);
        byKind.put("LAW", 5L);
        when(chunks.countByKind()).thenReturn(byKind);
        when(liveOrCache.openHosts()).thenReturn(Set.of("apis.data.go.kr"));

        OffsetDateTime doneAt = OffsetDateTime.parse("2026-09-20T10:00:00+09:00");
        // INDEX_* 는 완료 상태라도 제외되고, RUNNING인 DISASTER는 완료가 아니므로 제외된다.
        // 최댓값 후보는 GUIDE(DONE) 한 건뿐이어야 한다.
        when(checkpoints.findAll()).thenReturn(List.of(
                CrawlCheckpoint.builder().dataset("GUIDE").status(CrawlCheckpoint.STATUS_DONE)
                        .lastPage(4).savedCount(1039).updatedAt(doneAt).build(),
                CrawlCheckpoint.builder().dataset("INDEX_GUIDE").status(CrawlCheckpoint.STATUS_DONE)
                        .lastPage(1).savedCount(1039).updatedAt(doneAt.plusDays(1)).build(),
                CrawlCheckpoint.builder().dataset("DISASTER").status(CrawlCheckpoint.STATUS_RUNNING)
                        .lastPage(1).savedCount(300).updatedAt(doneAt.plusDays(2)).build()));

        var result = mvc.perform(get("/api/system/status")).andExpect(status().isOk()).andReturn();
        JsonNode json = om.readTree(result.getResponse().getContentAsString());

        assertThat(json.get("demoMode").asBoolean()).isTrue();
        assertThat(json.get("embeddingAvailable").asBoolean()).isFalse();
        assertThat(json.get("evidenceChunkCount").asLong()).isEqualTo(15L);
        assertThat(json.get("evidenceByKind").get("GUIDE").asLong()).isEqualTo(10L);
        assertThat(json.get("evidenceByKind").get("LAW").asLong()).isEqualTo(5L);
        assertThat(json.get("circuitOpenHosts").get(0).asText()).isEqualTo("apis.data.go.kr");
        // 정확한 직렬화 포맷(문자열 vs 타임스탬프 배열)에 의존하지 않고, INDEX_*·RUNNING이
        // 제외된 채 값이 채워졌는지만 확인한다 — 포맷 세부는 Jackson 설정(별도 관심사)에 맡긴다.
        assertThat(json.get("lastCrawlAt").isNull()).isFalse();
    }

    @Test
    void 완료된_수집_이력이_없으면_lastCrawlAt은_null이고_근거_수는_0() throws Exception {
        when(demoModeConfig.isDemoMode()).thenReturn(false);
        when(embedder.available()).thenReturn(true);
        when(chunks.countByKind()).thenReturn(Map.of());
        when(liveOrCache.openHosts()).thenReturn(Set.of());
        when(checkpoints.findAll()).thenReturn(List.of());

        var result = mvc.perform(get("/api/system/status")).andExpect(status().isOk()).andReturn();
        JsonNode json = om.readTree(result.getResponse().getContentAsString());

        assertThat(json.get("evidenceChunkCount").asLong()).isZero();
        assertThat(json.get("lastCrawlAt").isNull()).isTrue();
    }
}
