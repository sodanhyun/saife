package io.saife.evidence.chunk;

/** `[맥락 한 줄]\n원문` 형식. 검색용이므로 모델 컨텍스트·카드에 넣을 때는 벗긴다 */
public final class ContextualPrefix {
    private ContextualPrefix() {}
    static final int MAX_PREFIX = 200;

    public static String of(String context, String text) {
        return "[" + context.strip() + "]\n" + text;
    }

    public static String strip(String text) {
        if (text == null) return "";
        String r = text;
        while (r.startsWith("[")) {
            int end = r.indexOf("]\n");
            if (end <= 0 || end > MAX_PREFIX) break;
            r = r.substring(end + 2);
        }
        return r;
    }
}
