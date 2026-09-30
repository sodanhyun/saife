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

    /**
     * 진입 컨텍스트(작업 신고 화면의 {@code ?equipmentId=})로 대화를 시작한 설비.
     *
     * <p>첫 턴에만 실린다({@code ChatRequest.equipmentId}). 자유 텍스트 매칭이
     * 실패·애매할 때 {@code findLocationEquipment}가 이 설비로 단축한다 —
     * 이미 화면에서 설비를 골라 들어온 문맥을 자유 텍스트 매칭의 한계 때문에
     * 버리지 않기 위해서다.
     */
    public static final String EQUIPMENT_ID = "equipmentId";

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

    public static Long equipmentId(ToolContext ctx) {
        Object v = raw(ctx, EQUIPMENT_ID);
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
