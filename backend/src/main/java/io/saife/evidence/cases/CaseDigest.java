package io.saife.evidence.cases;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 국내재해사례 원문에서 재해 개요, 원인, 대책을 뽑는다. <b>원문 문장을 자를 뿐 새로 쓰지 않는다.</b>
 *
 * <p>공단 재해사례는 연도마다 서식이 다르다("【원 인】…【대 책】", "[원인]…[대책]",
 * "3. 재해발생원인 가. … 4. 동종재해예방대책 가. …"). 절 머리를 찾아 그 사이를 항목 기호
 * (○, ㅇ, o, 가., 나.)로 나눈다. 절을 못 찾으면 원인과 대책은 비운다.
 */
public final class CaseDigest {

    private CaseDigest() {}

    /**
     * @param summary  재해 개요 한 문장. 없으면 null
     * @param causes   원인 항목 (최대 3). 머리말과 원문 설명 한 줄
     * @param measures 대책 항목 (최대 3)
     * @param year     발생 연도 표기 (예: "2001년"). 없으면 null
     */
    public record Digest(String summary, List<Item> causes, List<Item> measures, String year) {
        public boolean hasCauseAndMeasure() {
            return !causes.isEmpty() && !measures.isEmpty();
        }
    }

    /**
     * @param head   머리말 (예: "작업방법 불량")
     * @param detail 원문 설명 첫 문장. 없으면 null
     */
    public record Item(String head, String detail) {}

    private static final int MAX_ITEMS = 3;
    private static final int ITEM_MAX = 46;
    private static final int DETAIL_MAX = 90;

    private static final Pattern CAUSE = Pattern.compile(
            "【\\s*원\\s*인\\s*】|\\[\\s*원\\s*인\\s*]|재\\s*해\\s*발\\s*생\\s*원\\s*인|재\\s*해\\s*원\\s*인|사\\s*고\\s*원\\s*인|발\\s*생\\s*원\\s*인");
    private static final Pattern MEASURE = Pattern.compile(
            "【\\s*대\\s*책\\s*】|\\[\\s*대\\s*책\\s*]|동\\s*종\\s*재\\s*해\\s*예\\s*방\\s*대\\s*책|재\\s*해\\s*예\\s*방\\s*대\\s*책|예\\s*방\\s*대\\s*책|재\\s*발\\s*방\\s*지\\s*대\\s*책|방\\s*지\\s*대\\s*책");
    /** 항목 기호: ○ ㅇ o ● □ ◦ ▶, 가. 나. …, 1) 2) */
    private static final Pattern BULLET = Pattern.compile("(?:^|\\s)(?:[○ㅇ●□◦▶]|o(?=\\s)|[가-하]\\.|\\d\\))\\s*");
    /** 항목 머리말이 끝나는 말. 이 말까지가 한 줄 요약이다 */
    private static final Pattern HEAD = Pattern.compile(
            "^(.{2,40}?(?:미설치|미착용|미부착|미사용|미실시|미배치|미지정|미준수|미흡|미비|미확보|불량|부적정|부적절|소홀|결여|부재|착용|설치|부착|확보|철저|실시|사용|지정|배치|금지|준수|강화|개선|교육|점검|조치|관리))(?=\\s|$|[,.])");
    private static final Pattern FIGURE = Pattern.compile("\\s*\\[그림\\s*\\d+\\s*]\\s*");
    private static final Pattern YEAR = Pattern.compile("날짜\\s*:\\s*(\\d{4})년");
    private static final String[] SUMMARY_END = {"재해임", "재해이다", "사망함", "사망하였음", "추락함", "부상함"};
    private static final String[] SUMMARY_START = {"■", "재해개요", "재 해 개 요", "재해내용요약", "요약", "】", "→"};

    public static Digest parse(String contents) {
        if (contents == null || contents.isBlank()) {
            return new Digest(null, List.of(), List.of(), null);
        }
        // 화면 표기 규칙: 원문의 가운뎃점(·, ㆍ)은 슬래시로 ("승·하강용" → "승/하강용")
        String text = contents.replaceAll("[│┌┐└┘─]", " ").replaceAll("\\s*[·ㆍ]\\s*", "/")
                .replaceAll("\\s+", " ").strip();

        List<Item> causes = List.of();
        List<Item> measures = List.of();
        Matcher c = CAUSE.matcher(text);
        if (c.find()) {
            Matcher m = MEASURE.matcher(text);
            if (m.find(c.end())) {
                causes = items(text.substring(c.end(), m.start()));
                measures = items(text.substring(m.end(), Math.min(text.length(), m.end() + 900)));
            }
        }
        Matcher y = YEAR.matcher(text);
        return new Digest(summary(text), causes, measures, y.find() ? y.group(1) + "년" : null);
    }

    /** 재해 개요: "…재해임"으로 끝나는 첫 문장. 앞쪽 머리표(■, 개요, 요약)까지 거슬러 올라가 자른다 */
    static String summary(String text) {
        int end = -1;
        String endWord = null;
        for (String w : SUMMARY_END) {
            int i = text.indexOf(w);
            if (i > 0 && (end < 0 || i < end)) {
                end = i;
                endWord = w;
            }
        }
        if (end < 0) return null;
        int start = 0;
        for (String mark : SUMMARY_START) {
            int i = text.lastIndexOf(mark, end);
            if (i >= 0) start = Math.max(start, i + mark.length());
        }
        String found = FIGURE.matcher(text.substring(start, end + endWord.length())).replaceAll(" ")
                .replaceFirst("^[\\d.\\s:■\\-]+", "").strip();
        if (found.length() < 15) return null;
        if (found.length() > 170) found = cut(found, 170);
        return found.endsWith(".") || found.endsWith("…") ? found : found + ".";
    }

    static List<Item> items(String section) {
        List<Item> out = new ArrayList<>();
        for (String raw : BULLET.split(" " + section)) {
            String t = FIGURE.matcher(raw).replaceAll(" ").replaceAll("\\s*\\d+\\.\\s*$", "").strip();
            if (t.length() < 4) continue;
            // "작업방법 불량 - 피재자가 …" 형식은 대시 앞이 머리말, 뒤가 설명이다
            int dash = t.indexOf(" - ");
            String head = (dash > 1 ? t.substring(0, dash) : headOf(t)).replaceAll("[\\-:]+$", "").strip();
            String rest = dash > 1 ? t.substring(dash + 3) : t.substring(Math.min(t.length(), headOf(t).length()));
            if (head.length() < 3 || out.stream().anyMatch(i -> i.head().equals(head))) continue;
            out.add(new Item(head, detailOf(rest)));
            if (out.size() == MAX_ITEMS) break;
        }
        return out;
    }

    /** 설명 첫 문장. 다음 "-" 항목 앞에서 끊는다 */
    private static String detailOf(String rest) {
        String t = rest.replaceFirst("^[\\s\\-:,.]+", "");
        int dash = t.indexOf(" - ");
        if (dash > 0) t = t.substring(0, dash);
        int stop = indexOfAny(t, "함.", "음.", "임.", "함 ", "음 ", "임 ");
        if (stop > 0) t = t.substring(0, stop + 1);
        t = t.strip();
        if (t.length() < 8 || t.endsWith("…")) return t.length() < 8 ? null : t;
        return t.length() > DETAIL_MAX ? cut(t, DETAIL_MAX) : t;
    }

    private static String headOf(String t) {
        Matcher h = HEAD.matcher(t);
        if (h.find()) return h.group(1);
        int stop = indexOfAny(t, "함.", "음.", "임.", ". ");
        String s = stop > 0 ? t.substring(0, stop + 1) : t;
        return s.length() > ITEM_MAX ? cut(s, ITEM_MAX) : s;
    }

    private static int indexOfAny(String t, String... keys) {
        int best = -1;
        for (String k : keys) {
            int i = t.indexOf(k);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /** 단어 경계에서 자르고 말줄임 */
    private static String cut(String s, int max) {
        int sp = s.lastIndexOf(' ', max);
        return (sp > max / 2 ? s.substring(0, sp) : s.substring(0, max)).strip() + "…";
    }
}
