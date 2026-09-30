package io.saife.evidence.search;

import java.util.*;

/** 가중 Reciprocal Rank Fusion. score[id] += w / (K + rank + 1). Inufleet HybridSearchService 이식 */
public final class HybridRrf {
    private HybridRrf() {}

    /** 정렬 대상 하나. bestRank는 벡터·키워드 랭크 중 더 좋은(작은) 쪽 — 한쪽에만 있으면 그 랭크 그대로 */
    record Entry(long id, double score, int bestRank) {}

    /** 점수 내림차순 → bestRank 오름차순 → id 오름차순. HashMap entrySet 정렬의 비결정성을 없앤다 */
    static Comparator<Entry> order() {
        return Comparator.comparingDouble(Entry::score).reversed()
                .thenComparingInt(Entry::bestRank)
                .thenComparingLong(Entry::id);
    }

    public static List<ChunkHit> fuse(List<ChunkHit> vector, List<ChunkHit> keyword, int limit) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, Integer> bestRank = new HashMap<>();
        Map<Long, ChunkHit> docs = new LinkedHashMap<>();
        for (int i = 0; i < vector.size(); i++) {
            ChunkHit h = vector.get(i);
            scores.merge(h.id(), SearchPolicy.VECTOR_WEIGHT / (SearchPolicy.RRF_K + i + 1), Double::sum);
            bestRank.merge(h.id(), i, Math::min);
            // 양쪽 리스트에 다 있으면 벡터가 먼저 순회되므로 벡터 쪽 ChunkHit 메타데이터가 이긴다
            docs.putIfAbsent(h.id(), h);
        }
        for (int i = 0; i < keyword.size(); i++) {
            ChunkHit h = keyword.get(i);
            scores.merge(h.id(), SearchPolicy.KEYWORD_WEIGHT / (SearchPolicy.RRF_K + i + 1), Double::sum);
            bestRank.merge(h.id(), i, Math::min);
            docs.putIfAbsent(h.id(), h);
        }
        return scores.entrySet().stream()
                .map(e -> new Entry(e.getKey(), e.getValue(), bestRank.get(e.getKey())))
                .sorted(order())
                .limit(limit)
                .map(e -> docs.get(e.id()).withScore(e.score()))
                .toList();
    }
}
