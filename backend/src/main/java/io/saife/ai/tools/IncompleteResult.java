package io.saife.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 필수 항목이 빠져 도구가 처리하지 못했을 때 돌려주는 구조화된 결과.
 *
 * <p><b>에러 메시지가 곧 복구 지침이다.</b> 무엇이 잘못됐는지, 무엇을 기대하는지,
 * 어떻게 확인하는지, 다음에 무엇을 할지를 모두 담는다. 모델은 이걸 받아
 * 사용자에게 되묻고, 답을 얻으면 같은 도구를 다시 호출한다.
 *
 * <p>실측 근거: {@code docs/experiments/README.md}.
 * 27건 시험에서 이 형태를 받은 모델은 값을 지어낸 적이 0회이고,
 * 사용자가 "알아서 적당히 넣어서 처리해줘요"라고 압박해도 거부했다.
 *
 * <p>{@code howToFind}는 사용자가 "그걸 어떻게 확인해요?"라고 물을 때 값을 한다.
 */
public final class IncompleteResult {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IncompleteResult() {}

    /**
     * @param field      빠진 항목 이름 (슬롯 키)
     * @param expected   기대하는 값의 형태
     * @param why        왜 필요한지 — 모델이 사용자를 설득하는 데 쓴다
     * @param howToFind  확인 방법
     * @param example    올바른 입력 예시
     */
    public record MissingField(String field, String expected, String why,
                               String howToFind, String example) {}

    /**
     * 불완전 결과를 JSON으로 만든다.
     *
     * @param reason  왜 처리되지 않았는지 한 줄
     * @param missing 빠진 항목들
     * @param toolName 다시 호출해야 할 도구 이름
     */
    public static String of(String reason, List<MissingField> missing, String toolName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "INCOMPLETE");
        body.put("reason", reason);

        List<Map<String, String>> fields = new ArrayList<>();
        for (MissingField m : missing) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("field", m.field());
            f.put("expected", m.expected());
            if (m.why() != null) f.put("why", m.why());
            if (m.howToFind() != null) f.put("how_to_find", m.howToFind());
            if (m.example() != null) f.put("example", m.example());
            fields.add(f);
        }
        body.put("missing", fields);

        body.put("next_action",
                "작업자에게 한 번에 하나씩 물어본 뒤, 이미 채운 값들과 함께 "
                        + toolName + "을(를) 다시 호출하세요.");
        body.put("do_not",
                "값을 추정하거나 기본값으로 채우지 마세요. 작업자가 모른다고 하거나 "
                        + "빨리 진행하자고 해도 확인 방법을 안내하고 기다리세요.");

        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"status\":\"INCOMPLETE\",\"reason\":\"" + reason + "\"}";
        }
    }

    /** 이 결과가 불완전 결과인지 — 루프가 UI 힌트를 발행할지 판단할 때 쓴다 */
    public static boolean isIncomplete(String toolResult) {
        return toolResult != null && toolResult.contains("\"status\":\"INCOMPLETE\"");
    }

    /** 첫 번째 빠진 항목의 이름. UI 힌트용이며 흐름을 제어하지 않는다 */
    public static String firstMissingField(String toolResult) {
        if (!isIncomplete(toolResult)) {
            return null;
        }
        try {
            Map<?, ?> parsed = MAPPER.readValue(toolResult, Map.class);
            Object missing = parsed.get("missing");
            if (missing instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> first) {
                Object field = first.get("field");
                return field != null ? field.toString() : null;
            }
        } catch (Exception ignored) {
            // UI 힌트가 실패해도 대화는 계속된다
        }
        return null;
    }
}
