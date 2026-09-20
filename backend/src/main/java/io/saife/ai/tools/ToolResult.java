package io.saife.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 도구 반환값을 Gemini 호환 JSON 형식으로 감싸는 유틸리티.
 * Spring AI Google Genai 어댑터는 도구 결과를 JSON으로 파싱하므로,
 * plain text를 직접 반환하면 JsonParseException이 발생한다.
 */
public class ToolResult {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 텍스트 결과를 {"result": "텍스트"} 형태의 JSON 문자열로 감싼다.
     * null 입력 시 {"result": null}을 반환한다.
     */
    public static String of(String text) {
        try {
            if (text == null) {
                return "{\"result\":null}";
            }
            return MAPPER.writeValueAsString(Map.of("result", text));
        } catch (Exception e) {
            return "{\"result\":\"도구 실행 결과를 반환할 수 없습니다.\"}";
        }
    }
}
