package io.saife.ai.agent;

import org.springframework.ai.chat.model.ToolContext;

/**
 * Spring AI {@link ToolContext}에 실어 나르는 키.
 *
 * <p>도구는 stateless여야 하지만 대화 식별자와 사업장은 알아야 한다.
 * ThreadLocal을 쓰지 않는 이유: 되묻기 턴에서 대화가 HTTP 경계를 넘어 중단·재개되므로
 * 스레드가 유지된다는 보장이 없다.
 */
public final class AgentContextKeys {

    /** SSE correlationId이자 conversation.id */
    public static final String CONVERSATION_ID = "conversationId";

    /** SSE 세션 식별자 */
    public static final String SESSION_ID = "sessionId";

    /** 가상 사업장 1곳이라 사실상 상수지만, 컨텍스트로 넘겨 도구를 stateless하게 둔다 */
    public static final String SITE_ID = "siteId";

    /** 이 대화가 만들고 있는 작업계획서 (없으면 아직 생성 전) */
    public static final String WORK_PLAN_ID = "workPlanId";

    private AgentContextKeys() {}

    public static String conversationId(ToolContext ctx) {
        return str(ctx, CONVERSATION_ID);
    }

    public static String sessionId(ToolContext ctx) {
        return str(ctx, SESSION_ID);
    }

    public static Long siteId(ToolContext ctx) {
        Object v = raw(ctx, SITE_ID);
        if (v == null) {
            return null;
        }
        return (v instanceof Number n) ? n.longValue() : Long.valueOf(v.toString());
    }

    public static Long workPlanId(ToolContext ctx) {
        Object v = raw(ctx, WORK_PLAN_ID);
        if (v == null) {
            return null;
        }
        return (v instanceof Number n) ? n.longValue() : Long.valueOf(v.toString());
    }

    private static Object raw(ToolContext ctx, String key) {
        if (ctx == null || ctx.getContext() == null) {
            return null;
        }
        return ctx.getContext().get(key);
    }

    private static String str(ToolContext ctx, String key) {
        Object v = raw(ctx, key);
        return v != null ? v.toString() : null;
    }
}
