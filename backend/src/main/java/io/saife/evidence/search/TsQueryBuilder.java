package io.saife.evidence.search;

import java.util.*;

/**
 * 자연어 질의 → `to_tsquery('simple', …)` 식.
 *
 * <p>Inufleet의 `plainto_tsquery`(모든 토큰 AND)는 조사가 붙은 한국어에서 거의 안 맞았다.
 * 여기서는 토큰을 OR로 묶고, 조사를 벗긴 변형을 함께 넣는다.
 */
public final class TsQueryBuilder {
    private TsQueryBuilder() {}
    private static final String[] PARTICLES = {"에서의", "으로는", "에서는", "에서", "으로", "부터", "까지", "에게", "은", "는", "이", "가", "을", "를", "와", "과", "의", "도", "에", "로"};

    /**
     * 분리 문자 클래스. 기존 ASCII 구두점에 더해 Unicode 구두점 전체(\p{P})와,
     * 그 범주에 안 잡히는 가운뎃점·문자류 기호(ㆍU+318D는 Letter, ～U+FF5E는 Symbol)를
     * 명시적으로 추가한다. "안전대·안전모", "（협착）" 같은 입력에서 구두점이 토큰에 섞이는 걸 막는다.
     */
    private static final String SPLIT_REGEX = "[\\s,./()\\[\\]{}:;!?\"'|&<>~`^*+=\\\\\\-\\p{P}\\u318D\\uFF5E]+";

    public static List<String> tokens(String query) {
        if (query == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : query.split(SPLIT_REGEX)) {
            String t = raw.strip();
            if (t.length() < 2) continue;
            out.add(t);
            if (out.size() >= SearchPolicy.KEYWORD_MAX_TOKENS) break;
        }
        return new ArrayList<>(out);
    }

    public static String build(String query) {
        List<String> toks = tokens(query);
        if (toks.isEmpty()) return "";
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String t : toks) {
            terms.add(t);
            String stripped = stripParticle(t);
            if (stripped != null) terms.add(stripped);
        }
        StringJoiner sj = new StringJoiner(" | ");
        // 토크나이저가 이미 '(작은따옴표)를 분리 문자로 쳐서 토큰에서 없앤다. 방어적으로 한 번 더 제거
        terms.forEach(t -> sj.add("'" + t.replace("'", "") + "'"));
        return sj.toString();
    }

    /** 뒤에 붙은 조사를 하나 벗긴다. 남는 길이가 2 미만이면 null */
    static String stripParticle(String token) {
        for (String p : PARTICLES) {
            if (token.endsWith(p) && token.length() - p.length() >= 2) return token.substring(0, token.length() - p.length());
        }
        return null;
    }
}
