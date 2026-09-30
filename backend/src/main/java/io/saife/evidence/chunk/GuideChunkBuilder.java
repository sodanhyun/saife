package io.saife.evidence.chunk;

import io.saife.evidence.EvidenceKind;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * KOSHA GUIDE PDF 텍스트 → 표지 child 1 + 섹션 parent/child.
 *
 * <p>Inufleet의 Tika 처리기에서 제목 휴리스틱과 "섹션 제목이 없으면 연속 5개를 parent로"를 가져왔다.
 * 다른 점: parent는 임베딩하지 않으므로 텍스트만 만들고 {@code searchable=false}.
 */
public final class GuideChunkBuilder {
    private GuideChunkBuilder() {}

    /** 제목줄: 제N장/제N절/제N조, 로마숫자 — 60자 이하이며 . , 로 끝나지 않는 줄 */
    private static final Pattern HEADING_TITLE = Pattern.compile(
            "^(제\\s?\\d+\\s?[장절조]|[IVXⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[.)]\\s+\\S).{0,60}$");
    /** 숫자 접두(1. / 1.2 / 1)) 제목줄. 번호 매긴 본문 문장("3. 작업자는 …해야 한다")과 구분하기 위해
     * 제목/로마숫자보다 엄격하게 본다 — {@link #isNumericHeading} 참고 */
    private static final Pattern HEADING_NUMERIC = Pattern.compile("^\\d+(\\.\\d+)*[.)]?\\s+\\S.*$");
    private static final double MIN_QUALITY = 0.3;

    public static List<ChunkDraft> build(Long guideId, String guideNo, String guideName, LocalDate announcedOn, List<String> pages) {
        List<ChunkDraft> out = new ArrayList<>();
        List<String> good = pages.stream().filter(p -> PdfTextExtractor.quality(p) >= MIN_QUALITY).toList();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("guideNo", guideNo);
        base.put("guideName", guideName);
        if (announcedOn != null) base.put("announcedOn", announcedOn.toString());

        // 표지: 제목 + 앞 2페이지(적용범위·목적)
        // 표지는 `#0`, 본문 child는 `#c{n}`, parent는 `#p{n}` — 접두어로 구분한다.
        String head = String.join("\n", good.stream().limit(2).toList());
        String cover = (guideName + "\n" + head).strip();
        if (cover.length() > ChunkPolicy.COVER_MAX) cover = cover.substring(0, ChunkPolicy.COVER_MAX);
        out.add(ChunkDraft.child(EvidenceKind.GUIDE, guideId, guideNo + "#0", null, "표지",
                "[KOSHA GUIDE " + guideNo + "] " + guideName, cover, withPage(base, 1, "표지")));

        // 섹션 분할
        List<Section> sections = splitSections(good);
        int p = 0, c = 0;
        for (Section s : sections) {
            List<String> pieces = ChunkOverlapUtil.addOverlap(splitToChildren(s.text));
            if (pieces.isEmpty()) continue;
            String parentKey = null;
            if (pieces.size() > 1) {
                p++;
                parentKey = guideNo + "#p" + p;
                out.add(ChunkDraft.parent(EvidenceKind.GUIDE, guideId, parentKey, s.title,
                        "[KOSHA GUIDE " + guideNo + "] " + guideName + " — " + s.title, s.text, withPage(base, s.page, s.title)));
            }
            for (String piece : pieces) {
                c++;
                out.add(ChunkDraft.child(EvidenceKind.GUIDE, guideId, guideNo + "#c" + c, parentKey, s.title,
                        "[KOSHA GUIDE " + guideNo + "] " + guideName + " — " + s.title, piece, withPage(base, s.page, s.title)));
            }
        }
        return out;
    }

    private record Section(String title, int page, String text) {}

    /** 제목줄마다 섹션을 연다. 제목이 하나도 없으면 문단 5개씩 묶는다. 본문 없는 섹션은 버린다 */
    static List<Section> splitSections(List<String> pages) {
        List<Section> out = new ArrayList<>();
        String title = "본문";
        int page = 1;
        StringBuilder buf = new StringBuilder();
        boolean anyHeading = false;
        for (int i = 0; i < pages.size(); i++) {
            for (String line : pages.get(i).split("\n")) {
                String t = line.strip();
                if (isHeading(t)) {
                    anyHeading = true;
                    flush(out, title, page, buf);
                    title = t; page = i + 1;
                } else {
                    buf.append(line).append('\n');
                }
            }
        }
        flush(out, title, page, buf);
        if (!anyHeading) {
            // 제목이 없으면 문단(빈 줄) 단위로 PARENT_MAX_CHILDREN개씩 묶는다.
            // 문단을 페이지별로 쪼갠 뒤 태그를 붙여, 그룹의 page가 항상 1로 고정되지 않고
            // 그룹의 첫 문단이 속한 실제 페이지를 따르게 한다.
            List<Para> paras = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                for (String para : pages.get(i).split("\n\\s*\n")) {
                    String t = para.strip();
                    if (!t.isEmpty()) paras.add(new Para(t, i + 1));
                }
            }
            List<Section> grouped = new ArrayList<>();
            for (int i = 0; i < paras.size(); i += ChunkPolicy.PARENT_MAX_CHILDREN) {
                List<Para> slice = paras.subList(i, Math.min(paras.size(), i + ChunkPolicy.PARENT_MAX_CHILDREN));
                List<String> texts = new ArrayList<>();
                for (Para pa : slice) texts.add(pa.text());
                String text = String.join("\n\n", texts).strip();
                if (!text.isBlank()) grouped.add(new Section("본문 " + (grouped.size() + 1), slice.get(0).page(), text));
            }
            return grouped;
        }
        return out;
    }

    private record Para(String text, int page) {}

    private static void flush(List<Section> out, String title, int page, StringBuilder buf) {
        String text = buf.toString().strip();
        buf.setLength(0);
        if (!text.isBlank()) out.add(new Section(title, page, text));
    }

    static boolean isHeading(String line) {
        if (line.isEmpty() || line.length() > 60) return false;
        if (line.endsWith(".") || line.endsWith(",")) return false;
        if (HEADING_TITLE.matcher(line).matches()) return true;
        return isNumericHeading(line);
    }

    /** 숫자 접두 제목: 40자 이하, 다/요/음으로 끝나지 않고(서술형 문장 배제), 공백 기준 토큰 6개 이하 */
    private static boolean isNumericHeading(String line) {
        if (line.length() > 40) return false;
        if (line.endsWith("다") || line.endsWith("요") || line.endsWith("음")) return false;
        if (line.split("\\s+").length > 6) return false;
        return HEADING_NUMERIC.matcher(line).matches();
    }

    /** 문단 경계를 존중하며 CHILD_TARGET까지 모으고, CHILD_MAX 초과는 강제 분할. 마지막 조각이 CHILD_MIN 미만이면 앞에 합친다 */
    static List<String> splitToChildren(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n\\s*\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            if (cur.length() + p.length() + 1 > ChunkPolicy.CHILD_TARGET && cur.length() >= ChunkPolicy.CHILD_MIN) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            }
            cur.append(p).append("\n\n");
        }
        if (!cur.isEmpty()) out.add(cur.toString().strip());
        // 초과분 강제 분할
        List<String> sized = new ArrayList<>();
        for (String piece : out) {
            if (piece.length() <= ChunkPolicy.CHILD_MAX) { sized.add(piece); continue; }
            for (int i = 0; i < piece.length(); i += ChunkPolicy.CHILD_TARGET) {
                sized.add(piece.substring(i, Math.min(piece.length(), i + ChunkPolicy.CHILD_TARGET)));
            }
        }
        // 짧은 꼬리 합치기
        if (sized.size() >= 2 && sized.get(sized.size() - 1).length() < ChunkPolicy.CHILD_MIN) {
            String tail = sized.remove(sized.size() - 1);
            sized.set(sized.size() - 1, sized.get(sized.size() - 1) + "\n\n" + tail);
        }
        return sized;
    }

    private static Map<String, Object> withPage(Map<String, Object> base, int page, String section) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.put("page", page);
        m.put("section", section);
        return m;
    }
}
