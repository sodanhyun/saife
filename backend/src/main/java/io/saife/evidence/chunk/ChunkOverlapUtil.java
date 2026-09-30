package io.saife.evidence.chunk;

import java.util.ArrayList;
import java.util.List;

/** 이전 청크의 꼬리 200자를 문장 경계에서 잘라 다음 청크 앞에 붙인다. 경계 단어가 잘려 검색에서 빠지는 것을 막는다 */
public final class ChunkOverlapUtil {
    private ChunkOverlapUtil() {}

    public static List<String> addOverlap(List<String> texts) {
        if (texts.size() <= 1) return texts;
        List<String> out = new ArrayList<>(texts.size());
        out.add(texts.get(0));
        for (int i = 1; i < texts.size(); i++) {
            String tail = tailForOverlap(texts.get(i - 1));
            out.add(tail.isBlank() ? texts.get(i) : tail + "\n" + texts.get(i));
        }
        return out;
    }

    /** 꼬리 200자 중 첫 문장 경계(. \n ? ! 다) 뒤부터. 경계가 없으면 200자 전체.
     * 경계가 꼬리의 마지막 위치면 뒤에 남는 글자가 없어 오버랩도 없다(빈 문자열).
     * 200자 이하 텍스트는 빈 문자열 */
    public static String tailForOverlap(String text) {
        if (text == null || text.length() <= ChunkPolicy.OVERLAP_CHARS) return "";
        String tail = text.substring(text.length() - ChunkPolicy.OVERLAP_CHARS);
        for (int j = 0; j < tail.length(); j++) {
            char c = tail.charAt(j);
            if (c == '.' || c == '\n' || c == '?' || c == '!' || c == '다') {
                return tail.substring(j + 1).trim();
            }
        }
        return tail.trim();
    }
}
