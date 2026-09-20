package io.saife.ai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 대화 한 건의 도구 호출 순번과 기록을 모으는 컨텍스트.
 *
 * ThreadLocal을 쓰지 않는 이유: 되묻기 턴에서 대화가 HTTP 경계를 넘어 중단·재개되므로
 * 스레드가 유지된다는 보장이 없다. conversationId를 키로 잡는다.
 *
 * 대화가 끝나면 {@link #drain}으로 비운다. 비우지 않으면 누수다.
 */
public final class ToolCallContext {

    private static final Map<String, AtomicInteger> ORDERS = new ConcurrentHashMap<>();
    private static final Map<String, List<Record>> RECORDS = new ConcurrentHashMap<>();

    private ToolCallContext() {}

    /**
     * @param toolName     도구 이름
     * @param paramsJson   호출 파라미터 JSON
     * @param callOrder    대화 내 호출 순번 (1부터)
     * @param durationMs   실행 시간
     * @param success      성공 여부
     * @param errorMessage 실패 사유 (성공이면 null)
     */
    public record Record(String toolName, String paramsJson, int callOrder,
                         long durationMs, boolean success, String errorMessage) {}

    public static int nextCallOrder(String conversationId) {
        if (conversationId == null) {
            return 0;
        }
        return ORDERS.computeIfAbsent(conversationId, k -> new AtomicInteger()).incrementAndGet();
    }

    public static void record(String conversationId, Record record) {
        if (conversationId == null) {
            return;
        }
        RECORDS.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(record);
    }

    /** 기록을 꺼내면서 비운다. 대화 종료 시 호출해 tool_call 테이블에 적재한다. */
    public static List<Record> drain(String conversationId) {
        ORDERS.remove(conversationId);
        List<Record> out = RECORDS.remove(conversationId);
        return out != null ? out : List.of();
    }

    /** 타임아웃으로 버려진 대화 정리용. */
    public static void discard(String conversationId) {
        ORDERS.remove(conversationId);
        RECORDS.remove(conversationId);
    }
}
