package io.saife.evidence.chunk;

import io.saife.evidence.EvidenceKind;
import io.saife.publicapi.domain.PublicCase;
import java.util.LinkedHashMap;
import java.util.Map;

/** 사례 1건 = 청크 1개. 제목에 축·업종 라벨을 넣어 키워드 검색이 "추락 제조업"으로도 맞게 한다 */
public final class CaseChunkBuilder {
    private CaseChunkBuilder() {}

    public static ChunkDraft build(PublicCase c) {
        EvidenceKind kind = "FATALITY".equals(c.getSource()) ? EvidenceKind.CASE_FATALITY : EvidenceKind.CASE_DISASTER;
        StringBuilder title = new StringBuilder();
        if (c.getAccidentType() != null) title.append('[').append(c.getAccidentType().getSearchTerm()).append("] ");
        if (c.getBusiness() != null && !c.getBusiness().isBlank()) title.append('[').append(c.getBusiness()).append("] ");
        title.append(c.getKeyword() == null ? "" : c.getKeyword());
        String text = ((c.getKeyword() == null ? "" : c.getKeyword()) + " " + (c.getContents() == null ? "" : c.getContents())).strip();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("accidentType", c.getAccidentType() == null ? null : c.getAccidentType().name());
        meta.put("business", c.getBusiness());
        meta.put("region", c.getRegion());
        meta.put("occurredOn", c.getOccurredOn() == null ? null : c.getOccurredOn().toString());
        meta.put("hasImage", c.getImageUrl() != null && !c.getImageUrl().isBlank());
        return ChunkDraft.child(kind, c.getId(), c.getSource() + ":" + c.getSourceKey(), null, null,
                title.toString().strip(), text, meta);
    }
}
