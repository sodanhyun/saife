package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;

import io.saife.evidence.EvidenceKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParentChunkExpanderTest {
    private ChunkHit child(long id, Long parentId, double score) {
        return new ChunkHit(id, EvidenceKind.GUIDE, 1, "G#c" + id, "child", parentId, "s", "t", "child text " + id, Map.of(), score);
    }
    private ChunkHit parentRow(long id) {
        return new ChunkHit(id, EvidenceKind.GUIDE, 1, "G#p", "parent", null, "s", "T", "parent text", Map.of(), 0);
    }

    @Test
    void 같은_parent의_child들은_parent_하나로_합쳐지고_최고_점수를_유지한다() {
        EvidenceChunkRepository repo = mock(EvidenceChunkRepository.class);
        when(repo.findParents(anySet())).thenReturn(Map.of(100L, parentRow(100)));
        List<ChunkHit> out = new ParentChunkExpander(repo).expand(List.of(child(1, 100L, 0.9), child(2, 100L, 0.5), child(3, null, 0.7)));
        assertThat(out).hasSize(2);
        assertThat(out.get(0).id()).isEqualTo(100);
        assertThat(out.get(0).chunkLevel()).isEqualTo("parent");
        assertThat(out.get(0).text()).isEqualTo("parent text");
        assertThat(out.get(0).score()).isEqualTo(0.9);
        assertThat(out.get(1).id()).isEqualTo(3);
    }

    @Test
    void parent_조회_실패면_child_유지() {
        EvidenceChunkRepository repo = mock(EvidenceChunkRepository.class);
        when(repo.findParents(anySet())).thenReturn(Map.of());
        List<ChunkHit> out = new ParentChunkExpander(repo).expand(List.of(child(1, 100L, 0.9)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(1);
    }

    @Test
    void 순서는_점수_내림차순() {
        EvidenceChunkRepository repo = mock(EvidenceChunkRepository.class);
        when(repo.findParents(anySet())).thenReturn(Map.of(100L, parentRow(100)));
        List<ChunkHit> out = new ParentChunkExpander(repo).expand(List.of(child(3, null, 0.95), child(1, 100L, 0.9)));
        assertThat(out).extracting(ChunkHit::id).containsExactly(3L, 100L);
    }
}
