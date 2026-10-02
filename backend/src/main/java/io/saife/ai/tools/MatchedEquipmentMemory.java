package io.saife.ai.tools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대화별로 마지막에 확정된 설비 ID.
 *
 * <p>모델이 extractWorkPlan에 설비 ID를 넘길 때 대화에 없던 번호를 지어내는 경우가 있다
 * (2026-10-02 라이브 실측: 확정된 설비는 1번인데 101, 12를 넘겨 FK 위반으로 도구가 실패했다).
 * 도구는 넘어온 번호가 실제 설비인지 확인하고, 아니면 이 대화에서 확정된 설비를 쓴다.
 * 대화 수가 무대 시연 규모라 상한 없이 둔다.
 */
public final class MatchedEquipmentMemory {

    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private MatchedEquipmentMemory() {}

    public static void remember(String conversationId, Long equipmentId) {
        if (conversationId != null && equipmentId != null) {
            LAST.put(conversationId, equipmentId);
        }
    }

    public static Long last(String conversationId) {
        return conversationId == null ? null : LAST.get(conversationId);
    }
}
