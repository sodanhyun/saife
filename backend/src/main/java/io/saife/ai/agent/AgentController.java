package io.saife.ai.agent;

import io.saife.common.service.SseService;
import io.saife.workplan.service.SlotAnswerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 에이전트 대화 엔드포인트.
 *
 * <p>두 개가 한 쌍이다:
 * <ul>
 *   <li>{@code POST /api/agent/chat} — 대화 시작. SSE 스트림을 연다</li>
 *   <li>{@code POST /api/agent/{id}/slot} — 되묻기 답변. <b>중단된 턴을 재개한다</b></li>
 * </ul>
 *
 * <p>되묻기가 HTTP 경계를 넘기 때문에 두 요청이 필요하다.
 * 재개 요청은 {@code conversationId}로 중단된 턴을 찾아 새 스레드에서 이어 돌린다.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    /** 가상 사업장 1곳이라 상수. 테넌시 없음 */
    private static final Long DEMO_SITE_ID = 1L;

    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final AgentService agentService;
    private final SlotAnswerService slotAnswerService;
    private final SseService sseService;

    /** 워커 스레드는 여기서만 쓴다. SseEmitter를 붙잡고 사람을 기다리지 않는다 */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * @param message        작업자 발화
     * @param conversationId 이어갈 대화. 없으면 새로 시작한다.
     *                       되묻기 답변도 이 필드로 들어온다 — 별도 재개 API가 없다.
     * @param slotKey        되묻기 답변이면 어떤 슬롯인지 (선택). 기록용이며 흐름 제어는 하지 않는다
     */
    public record ChatRequest(String message, String conversationId, String slotKey) {}

    /**
     * 대화 한 턴.
     *
     * <p>되묻기 답변도 같은 엔드포인트로 들어온다. {@code conversationId}를 넘기면
     * 히스토리를 이어 붙인다 — 별도 재개 API가 없다.
     *
     * <p>{@code slotKey}가 오면 답변을 {@code work_plan_slot}에도 기록한다.
     * 대장 기록과 다르면 {@code conflicted}로 남고 조치 이행 상태가 갱신된다.
     * 이건 기록일 뿐이고 대화 흐름은 모델이 쥔다.
     */
    /**
     * 저장된 대화 기록. 새로고침 후 화면을 되살린다.
     *
     * <p>이게 없으면 새로고침 한 번에 모델이 맥락을 잃고 이미 아는 것을 다시 묻는다.
     */
    @GetMapping("/{conversationId}/transcript")
    public ResponseEntity<List<AgentService.TranscriptLine>> transcript(
            @PathVariable String conversationId) {
        return ResponseEntity.ok(agentService.transcript(conversationId));
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : request.conversationId();

        SseService.SseSession session = sseService.createSession(conversationId, STREAM_TIMEOUT_MS, null);

        log.info("[AGENT] 턴 시작 conversationId={} slot={}", conversationId, request.slotKey());

        executor.submit(() -> {
            if (request.slotKey() != null && !request.slotKey().isBlank()) {
                slotAnswerService.record(conversationId, request.slotKey(), request.message());
            }
            agentService.chat(conversationId, session.sessionId(), DEMO_SITE_ID, request.message());
        });

        return session.emitter();
    }
}
