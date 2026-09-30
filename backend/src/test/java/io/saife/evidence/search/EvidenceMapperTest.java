package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceMapperTest {

    // 최종 리뷰 F11: "캐시 MM-DD"는 청크 행이 캐시에 들어간 시각이어야 한다(오늘 날짜가 아니라)
    @Test
    void fetchedAt은_청크_행의_시각을_쓴다() {
        OffsetDateTime cachedAt = OffsetDateTime.of(2026, 9, 21, 10, 0, 0, 0, ZoneOffset.ofHours(9));
        ChunkHit h = new ChunkHit(1, EvidenceKind.CASE_DISASTER, 1, "DISASTER:1", "child", null, null, "t", "본문",
                Map.of(), 0.5, cachedAt);
        assertThat(EvidenceMapper.toEvidence(h, Origin.CACHE).fetchedAt()).isEqualTo(cachedAt);
    }

    @Test
    void 시각을_모르면_현재_시각으로_채운다() {
        ChunkHit h = new ChunkHit(1, EvidenceKind.CASE_DISASTER, 1, "DISASTER:1", "child", null, null, "t", "본문", Map.of(), 0.5);
        Evidence e = EvidenceMapper.toEvidence(h, Origin.CACHE);
        assertThat(e.fetchedAt()).isNotNull();
    }

    // 최종 리뷰 F2: 표시 점수는 [0,1]
    @Test
    void 표시_점수는_0에서_1로_자른다() {
        assertThat(EvidenceMapper.displayScore(1.07)).isEqualTo(1.0);
        assertThat(EvidenceMapper.displayScore(-0.2)).isEqualTo(0.0);
        assertThat(EvidenceMapper.displayScore(0.4567)).isEqualTo(0.457);
    }
}
