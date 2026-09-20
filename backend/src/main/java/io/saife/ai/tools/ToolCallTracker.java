package io.saife.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.common.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 도구 실행을 감싸서 SSE 트레이스 이벤트와 실행 기록을 자동으로 남기는 유틸리티.
 *
 * 이 클래스가 시연의 핵심이다. 심사위원은 "입력 → 판단 → 도구 실행 → 결과"를
 * 추론하는 게 아니라 눈으로 봐야 하고, 그 화면을 만드는 것이 여기서 쏘는
 * {@code ai.tool.start} / {@code ai.tool.done} 이벤트다.
 *
 * 측정값(duration_ms, success)은 화면의 트레이스 패널과 성과 지표(도구 체인 완주율)가
 * 같이 쓴다. 두 숫자가 어긋나지 않도록 출처를 하나로 둔다.
 */
@Slf4j
public final class ToolCallTracker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallTracker() {}

    /**
     * 도구 실행을 감싼다.
     *
     * @param toolName   도구 이름 (등록된 정식 이름)
     * @param params     호출 파라미터 (트레이스 패널에 표시됨)
     * @param sseService SSE 발행기
     * @param toolContext Spring AI ToolContext — conversationId / sessionId를 담고 있다
     * @param action     실제 도구 로직
     * @return 도구 결과 문자열 (반드시 JSON — {@link ToolResult#of} 참조)
     */
    public static String execute(String toolName,
                                 Map<String, Object> params,
                                 SseService sseService,
                                 ToolContext toolContext,
                                 Supplier<String> action) {

        String conversationId = ctx(toolContext, "conversationId");
        String sessionId = ctx(toolContext, "sessionId");
        int callOrder = ToolCallContext.nextCallOrder(conversationId);
        String paramsJson = serialize(params);

        // 실행 "전" 이벤트 — 패널에 도구가 점등되는 순간
        emit(sseService, sessionId, "ai.tool.start", conversationId, toolName,
                Map.of("toolName", toolName, "params", paramsJson, "callOrder", callOrder));

        long startMs = System.currentTimeMillis();
        boolean success = true;
        String errorMessage = null;

        try {
            String result = action.get();

            // 예외 없이 빈 문자열을 돌려주는 경우가 실제로 있다. 부분 실패로 기록한다.
            if (result == null || result.isBlank()) {
                success = false;
                errorMessage = "빈 응답: 도구가 데이터를 반환하지 못했습니다";
            }
            return result;

        } catch (Exception e) {
            success = false;
            errorMessage = sanitize(e);
            // Spring AI의 ToolExecutionExceptionProcessor가 받아서 JSON으로 감싼다.
            // ToolCallingConfig를 참조 — 여기서 삼키면 모델이 실패를 모른다.
            throw e;

        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            log.info("[TOOL] {} order={} success={} {}ms", toolName, callOrder, success, durationMs);

            Map<String, Object> payload = new HashMap<>();
            payload.put("toolName", toolName);
            payload.put("callOrder", callOrder);
            payload.put("success", success);
            payload.put("durationMs", durationMs);
            if (errorMessage != null) {
                payload.put("errorMessage", errorMessage);
            }
            emit(sseService, sessionId, "ai.tool.done", conversationId, toolName, payload);

            ToolCallContext.record(conversationId,
                    new ToolCallContext.Record(toolName, paramsJson, callOrder, durationMs, success, errorMessage));
        }
    }

    private static void emit(SseService sseService, String sessionId, String type,
                             String correlationId, String targetId, Map<String, Object> payload) {
        if (sseService == null || sessionId == null) {
            return; // 비-스트리밍 경로(테스트, 배치)에서는 조용히 넘어간다
        }
        try {
            sseService.send(sessionId, SseService.SseEvent.of(type, correlationId, targetId, payload));
        } catch (Exception e) {
            // 트레이스 실패가 도구 실패가 되면 안 된다
            log.warn("SSE 트레이스 발행 실패 — type={} tool={}: {}", type, targetId, e.getMessage());
        }
    }

    private static String ctx(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object v = toolContext.getContext().get(key);
        return v != null ? v.toString() : null;
    }

    private static String serialize(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 스택트레이스·내부 경로가 화면이나 모델 컨텍스트로 새지 않게 한 줄로 줄인다. */
    private static String sanitize(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }
}
