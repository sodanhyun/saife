package io.saife.evidence.chunk;

import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.LawArticle;
import java.util.*;

/** 조(條) 하나의 항 목록 → parent(조 전체) + child(항). 항이 하나면 child만 */
public final class LawChunkBuilder {
    private LawChunkBuilder() {}

    public static List<ChunkDraft> build(List<LawArticle> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return List.of();
        LawArticle first = paragraphs.get(0);
        String base = first.getLawId() + ":" + first.getArticleNo() + ":" + first.getArticleSub();
        // parent는 조(條) 전체를 대표하므로 특정 항 번호(paragraphNo)를 갖지 않는다 — meta에 넣지 않는다
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lawId", first.getLawId());
        meta.put("lawName", first.getLawName());
        meta.put("articleNo", first.getArticleNo());
        meta.put("articleSub", first.getArticleSub());
        meta.put("effectiveOn", first.getEffectiveOn() == null ? null : first.getEffectiveOn().toString());
        List<ChunkDraft> out = new ArrayList<>();
        String parentKey = null;
        if (paragraphs.size() > 1) {
            parentKey = base + "#p";
            String joined = String.join("\n", paragraphs.stream().map(LawArticle::getText).toList());
            String articleTitle = first.articleCitation();
            out.add(ChunkDraft.parent(EvidenceKind.LAW, first.getId(), parentKey, articleTitle, articleTitle, joined, meta));
        }
        for (LawArticle p : paragraphs) {
            Map<String, Object> m = new LinkedHashMap<>(meta);
            m.put("paragraphNo", p.getParagraphNo());
            out.add(ChunkDraft.child(EvidenceKind.LAW, p.getId(), base + ":" + p.getParagraphNo(), parentKey,
                    first.getTitle(), p.citation(), p.citation() + "\n" + p.getText(), m));
        }
        return out;
    }
}
