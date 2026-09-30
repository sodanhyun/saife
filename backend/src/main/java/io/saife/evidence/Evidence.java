package io.saife.evidence;

import io.saife.evidence.live.Origin;
import java.time.OffsetDateTime;
import java.util.Map;

/** 근거 카드 한 장. 프론트 types/evidence.ts와 1:1 */
public record Evidence(int no, EvidenceKind kind, Long refId, String refKey, String title, String snippet,
                       String sourceUrl, String mediaUrl, String thumbnailUrl, Origin origin, double score,
                       OffsetDateTime fetchedAt, Map<String, Object> meta) {
    public Evidence withNo(int n) {
        return new Evidence(n, kind, refId, refKey, title, snippet, sourceUrl, mediaUrl, thumbnailUrl, origin, score, fetchedAt, meta);
    }
    /** 원장 중복 판정 키 */
    public String identity() { return kind + ":" + refKey; }
}
