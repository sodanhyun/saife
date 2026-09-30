package io.saife.evidence.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 모델 출력의 [#n] 인용. 원장에 없는 번호는 지운다(환각 인용 차단). 공백 변형은 [#n]으로 정규화 */
public final class CitationSanitizer {
    // 그룹1: [ 바로 앞의 공백 1개(있으면). 있는 번호를 남길 때는 그 공백까지 그대로 보존하고,
    // 없는 번호를 지울 때는 그 공백까지 함께 지워야 "지침 [#7]도" → "지침도"처럼 자연스러워진다.
    private static final Pattern CITE = Pattern.compile("(\\s?)\\[\\s*#\\s*(\\d{1,4})\\s*\\]");
    private CitationSanitizer() {}

    public static String sanitize(String text, Set<Integer> known) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = CITE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(2));
            if (known.contains(n)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "[#" + n + "]"));
                continue;
            }
            m.appendReplacement(sb, "");
            tidyRemovalSite(sb, m.end() < text.length() ? text.charAt(m.end()) : '\n');
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 최종 리뷰 F15 — 지운 인용 <b>자리에서만</b> 공백을 정리한다. 예전에는 답변 전체에
     * {@code "  +" → " "}를 걸어 들여쓴 하위 목록("  - 항목")까지 평평하게 만들었다.
     *
     * <p>지운 자리 바로 뒤가 문장부호면({@code "사례 [#9]."}) 앞 공백을 걷고, 뒤가 공백이면
     * ({@code "a  [#9] b"}) 앞 공백을 걷어 공백이 겹치지 않게 한다. 줄 머리의 들여쓰기는
     * 건드리지 않는다(공백 앞이 줄바꿈이거나 문자열 시작이면 그대로 둔다).
     */
    private static void tidyRemovalSite(StringBuilder sb, char next) {
        if (next != '.' && next != ',' && next != '!' && next != '?' && next != ' ' && next != '\t') return;
        int end = sb.length();
        int start = end;
        while (start > 0 && (sb.charAt(start - 1) == ' ' || sb.charAt(start - 1) == '\t')) start--;
        if (start == end || start == 0 || sb.charAt(start - 1) == '\n') return;
        sb.setLength(start);
    }

    public static List<Integer> citedNumbers(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = CITE.matcher(text);
        while (m.find()) out.add(Integer.parseInt(m.group(2)));
        return out;
    }
}
