package io.saife.evidence.search;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** child → parent 치환. 같은 parent는 1건(최고 점수). parent를 못 찾으면 child 유지(Inufleet은 여기서 child를 버렸다) */
@Component
@RequiredArgsConstructor
public class ParentChunkExpander {
    private final EvidenceChunkRepository repository;

    public List<ChunkHit> expand(List<ChunkHit> hits) {
        if (hits.isEmpty()) return hits;
        Set<Long> parentIds = new LinkedHashSet<>();
        for (ChunkHit h : hits) if ("child".equals(h.chunkLevel()) && h.parentId() != null) parentIds.add(h.parentId());
        Map<Long, ChunkHit> parents = repository.findParents(parentIds);
        Map<Long, ChunkHit> merged = new LinkedHashMap<>();   // key: 결과 id
        for (ChunkHit h : hits) {
            ChunkHit p = h.parentId() == null ? null : parents.get(h.parentId());
            if (p == null) { merged.putIfAbsent(h.id(), h); continue; }
            ChunkHit asParent = h.asParent(p.id(), p.text(), p.title());
            merged.merge(p.id(), asParent, (a, b) -> a.score() >= b.score() ? a : b);
        }
        return merged.values().stream().sorted(Comparator.comparingDouble(ChunkHit::score).reversed()).toList();
    }
}
