package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.EvidenceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HybridRrfTest {
    private ChunkHit hit(long id) {
        return new ChunkHit(id, EvidenceKind.CASE_DISASTER, id, "k" + id, "child", null, null, "t" + id, "x", Map.of(), 0);
    }

    private int indexOfId(List<ChunkHit> hits, long id) {
        for (int i = 0; i < hits.size(); i++) if (hits.get(i).id() == id) return i;
        return -1;
    }

    @Test
    void 양쪽에_있는_문서가_1위() {
        List<ChunkHit> out = HybridRrf.fuse(List.of(hit(1), hit(2), hit(3)), List.of(hit(3), hit(4)), 10);
        assertThat(out.get(0).id()).isEqualTo(3);
        assertThat(out).extracting(ChunkHit::id).doesNotHaveDuplicates();
        assertThat(out).hasSize(4);
    }

    @Test
    void 한쪽이_비면_다른_쪽_순서() {
        List<ChunkHit> out = HybridRrf.fuse(List.of(hit(1), hit(2)), List.of(), 10);
        assertThat(out).extracting(ChunkHit::id).containsExactly(1L, 2L);
    }

    @Test
    void limit을_지킨다_그리고_점수는_RRF() {
        List<ChunkHit> out = HybridRrf.fuse(List.of(hit(1), hit(2), hit(3)), List.of(hit(1)), 2);
        assertThat(out).hasSize(2);
        double expected = SearchPolicy.VECTOR_WEIGHT / (SearchPolicy.RRF_K + 1) + SearchPolicy.KEYWORD_WEIGHT / (SearchPolicy.RRF_K + 1);
        assertThat(out.get(0).score()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void 둘_다_비면_빈_리스트() {
        assertThat(HybridRrf.fuse(List.of(), List.of(), 5)).isEmpty();
    }

    // --- F1 fix round 1: 동점 정렬 결정성 ---

    @Test
    void 비교자는_점수_내림차순_다음_bestRank_오름차순() {
        List<HybridRrf.Entry> entries = new ArrayList<>(List.of(
                new HybridRrf.Entry(2L, 0.5, 3),
                new HybridRrf.Entry(1L, 0.5, 1),
                new HybridRrf.Entry(9L, 0.9, 5)));
        entries.sort(HybridRrf.order());
        assertThat(entries).extracting(HybridRrf.Entry::id).containsExactly(9L, 1L, 2L);
    }

    @Test
    void 비교자는_점수와_bestRank가_같으면_id_오름차순() {
        List<HybridRrf.Entry> entries = new ArrayList<>(List.of(
                new HybridRrf.Entry(5L, 0.4, 2),
                new HybridRrf.Entry(2L, 0.4, 2)));
        entries.sort(HybridRrf.order());
        assertThat(entries).extracting(HybridRrf.Entry::id).containsExactly(2L, 5L);
    }

    @Test
    void 진짜_동점이면_bestRank가_작은_문서가_앞선다() {
        // 0.6/(60+32+1) == 0.4/(60+1+1) (둘 다 1/155) — 벡터 랭크32 단독 히트 vs 키워드 랭크1 단독 히트
        List<ChunkHit> vector = new ArrayList<>();
        for (int i = 0; i < 32; i++) vector.add(hit(9000L + i)); // 채움, rank 0..31
        vector.add(hit(500L)); // rank 32 → docB(벡터만)
        List<ChunkHit> keyword = List.of(hit(9500L), hit(600L)); // rank0 채움, rank1 → docA(키워드만)

        List<ChunkHit> out = HybridRrf.fuse(vector, keyword, 100);
        int idxA = indexOfId(out, 600L);
        int idxB = indexOfId(out, 500L);
        assertThat(idxA).isGreaterThanOrEqualTo(0);
        assertThat(idxB).isGreaterThanOrEqualTo(0);
        assertThat(out.get(idxA).score()).isEqualTo(out.get(idxB).score());
        // docA는 bestRank=1(키워드 랭크1), docB는 bestRank=32(벡터 랭크32) → docA가 앞선다
        assertThat(idxA).isLessThan(idxB);
    }

    @Test
    void fuse는_같은_입력에_대해_항상_같은_순서를_낸다() {
        // HashMap 버킷 순서를 흔들 만한 id들을 섞어 넣는다
        List<ChunkHit> vector = List.of(hit(100000L), hit(3L), hit(77L));
        List<ChunkHit> keyword = List.of(hit(1L), hit(42L), hit(77L));
        List<ChunkHit> first = HybridRrf.fuse(vector, keyword, 10);
        for (int i = 0; i < 50; i++) {
            assertThat(HybridRrf.fuse(vector, keyword, 10)).isEqualTo(first);
        }
    }
}
