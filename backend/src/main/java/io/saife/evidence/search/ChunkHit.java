package io.saife.evidence.search;

import io.saife.evidence.EvidenceKind;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 검색 파이프라인을 흐르는 청크 한 건. score는 단계마다 의미가 다르다(유사도 → RRF → 리랭크 → 보정).
 *
 * <p>{@code fetchedAt}은 청크 행의 {@code updated_at} — 카드의 "캐시 MM-DD"가 오늘 날짜가 아니라
 * 실제로 캐시에 들어간 날을 말하게 한다(최종 리뷰 F11). 알 수 없으면 null.
 */
public record ChunkHit(long id, EvidenceKind kind, long refId, String refKey, String chunkLevel, Long parentId,
                       String sectionTitle, String title, String text, Map<String, Object> metadata, double score,
                       OffsetDateTime fetchedAt) {
    /** 캐시 시각을 모르는 경로(테스트·수동 조립)용 */
    public ChunkHit(long id, EvidenceKind kind, long refId, String refKey, String chunkLevel, Long parentId,
                    String sectionTitle, String title, String text, Map<String, Object> metadata, double score) {
        this(id, kind, refId, refKey, chunkLevel, parentId, sectionTitle, title, text, metadata, score, null);
    }
    public ChunkHit withScore(double s) {
        return new ChunkHit(id, kind, refId, refKey, chunkLevel, parentId, sectionTitle, title, text, metadata, s, fetchedAt);
    }
    /** parent로 치환할 때: id·텍스트·레벨만 바뀌고 나머지는 child 것을 유지 */
    public ChunkHit asParent(long parentRowId, String parentText, String parentTitle) {
        return new ChunkHit(parentRowId, kind, refId, refKey, "parent", null, sectionTitle, parentTitle, parentText, metadata, score, fetchedAt);
    }
}
