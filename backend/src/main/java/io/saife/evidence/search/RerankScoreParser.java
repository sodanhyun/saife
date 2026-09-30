package io.saife.evidence.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 리랭크 응답 → 점수 배열. 길이가 문서 수와 다르면 null(폴백). Inufleet은 int[] 경로에서 길이를 안 봐 꼬리가 잘렸다.
 *
 * <p>최종 리뷰 F2: 모델이 범위를 벗어난 값(12, -1)을 내면 [0,10]으로 자른다 — 자르지 않으면
 * {@code score/10}이 1.0을 넘어 "유사도 120%"가 된다. 숫자 문자열({@code "7"}, {@code "7.5"})도
 * 받는다(Flash가 JSON 모드에서도 가끔 따옴표로 감싼다). 소수는 반올림한다.
 */
public final class RerankScoreParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MIN = 0;
    private static final int MAX = 10;
    private RerankScoreParser() {}

    public static int[] parse(String content, int expected) {
        if (content == null) return null;
        int s = content.indexOf('['), e = content.lastIndexOf(']');
        if (s < 0 || e <= s) return null;
        try {
            JsonNode root = MAPPER.readTree(content.substring(s, e + 1));
            if (!root.isArray() || root.size() != expected) return null;
            int[] out = new int[expected];
            for (int i = 0; i < expected; i++) {
                JsonNode n = root.get(i);
                Integer v = scalar(n);
                if (v == null && n.isObject()) {
                    var it = n.fields();
                    while (it.hasNext() && v == null) v = scalar(it.next().getValue());
                }
                if (v == null) return null;
                out[i] = v;
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 숫자 또는 숫자 문자열 → [0,10]으로 자른 정수. 그 밖은 null */
    private static Integer scalar(JsonNode n) {
        double d;
        if (n.isNumber()) {
            d = n.doubleValue();
        } else if (n.isTextual()) {
            try {
                d = Double.parseDouble(n.textValue().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        } else {
            return null;
        }
        if (Double.isNaN(d)) return null;
        return (int) Math.max(MIN, Math.min(MAX, Math.round(d)));
    }
}
