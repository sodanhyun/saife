package io.saife.evidence.chunk;

import io.saife.evidence.EvidenceKind;
import java.util.Map;

/** DB에 넣기 전의 청크. refKey가 시드 재적재 시 매칭 키다. parent는 searchable=false */
public record ChunkDraft(EvidenceKind kind, Long refId, String refKey, String chunkLevel, String parentRefKey,
                         boolean searchable, String sectionTitle, String title, String text,
                         Map<String, Object> metadata) {

    public static ChunkDraft child(EvidenceKind kind, Long refId, String refKey, String parentRefKey,
                                   String sectionTitle, String title, String text, Map<String, Object> metadata) {
        return new ChunkDraft(kind, refId, refKey, ChunkPolicy.LEVEL_CHILD, parentRefKey, true, sectionTitle, title, text, metadata);
    }

    public static ChunkDraft parent(EvidenceKind kind, Long refId, String refKey, String sectionTitle,
                                    String title, String text, Map<String, Object> metadata) {
        return new ChunkDraft(kind, refId, refKey, ChunkPolicy.LEVEL_PARENT, null, false, sectionTitle, title, text, metadata);
    }

    public ChunkDraft withText(String newText) {
        return new ChunkDraft(kind, refId, refKey, chunkLevel, parentRefKey, searchable, sectionTitle, title, newText, metadata);
    }
}
