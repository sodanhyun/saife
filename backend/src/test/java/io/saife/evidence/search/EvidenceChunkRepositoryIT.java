package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.EvidenceKind;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EvidenceChunkRepositoryIT {
    @Autowired EvidenceChunkRepository repo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    // ruling R58: 커넥션마다 hnsw.ef_search=200 (application.yml hikari.connection-init-sql)
    @Test
    void 커넥션은_hnsw_ef_search_200으로_열린다() {
        assertThat(jdbc.queryForObject("show hnsw.ef_search", String.class)).isEqualTo("200");
    }

    private float[] unit(int hot) { float[] v = new float[SearchPolicy.EMBEDDING_DIMENSIONS]; v[hot] = 1f; return v; }

    @Test
    void 벡터_키워드_parent_라운드트립() {
        // F-IT1: 5433의 evidence_chunk는 이제 실제 시드(GUIDE 등 약 4만행)를 영구히 담고 있어
        // 절대값 단언이 깨진다 — 시작 시점 스냅샷을 찍고 이번 테스트가 넣은 3건(parent 1 + child 2)만큼만
        // 늘었는지 델타로 확인한다.
        long guideBefore = repo.countByKind().getOrDefault("GUIDE", 0L);
        List<Long> pid = repo.insertBatch(List.of(new EvidenceChunkRepository.ChunkRow(EvidenceKind.GUIDE, 1, "IT#p1", "parent", null, false, "s", "P", "parent text", Map.of(), null)));
        List<Long> cids = repo.insertBatch(List.of(
                new EvidenceChunkRepository.ChunkRow(EvidenceKind.GUIDE, 1, "IT#c1", "child", pid.get(0), true, "s", "사다리 지침", "사다리 작업 전 확인 사항", Map.of("accidentType", "FALL"), unit(0)),
                new EvidenceChunkRepository.ChunkRow(EvidenceKind.GUIDE, 1, "IT#c2", "child", pid.get(0), true, "s", "용접 지침", "용접 화기 작업", Map.of("accidentType", "FIRE"), unit(1))));
        List<ChunkHit> vec = repo.vectorSearch(unit(0), Set.of(EvidenceKind.GUIDE), null, 5);
        assertThat(vec).isNotEmpty();
        assertThat(vec.get(0).refKey()).isEqualTo("IT#c1");
        assertThat(vec.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(vec.get(0).fetchedAt()).as("F11: updated_at이 카드의 캐시 시각으로 실린다").isNotNull();
        List<ChunkHit> kw = repo.keywordSearch(TsQueryBuilder.build("사다리에서"), Set.of(EvidenceKind.GUIDE), "FALL", 5);
        assertThat(kw).extracting(ChunkHit::refKey).contains("IT#c1").doesNotContain("IT#c2");
        assertThat(repo.findParents(Set.of(pid.get(0)))).containsKey(pid.get(0));
        assertThat(repo.vectorSearch(unit(0), Set.of(EvidenceKind.GUIDE), "FIRE", 5)).extracting(ChunkHit::refKey).doesNotContain("IT#c1");

        // findIdByRefKey: 존재/부재 둘 다 확인
        assertThat(repo.findIdByRefKey(EvidenceKind.GUIDE, "IT#c1")).contains(cids.get(0));
        assertThat(repo.findIdByRefKey(EvidenceKind.GUIDE, "IT#does-not-exist")).isEmpty();

        // countByKind: 시드 사전 데이터와 무관하게 이번 테스트가 넣은 3건만큼만 늘어야 한다.
        assertThat(repo.countByKind().getOrDefault("GUIDE", 0L) - guideBefore).isEqualTo(3L);
    }

    @Test
    void insertBatch는_NUL_바이트가_섞인_text도_예외없이_저장하고_조회_결과에는_NUL이_없다() {
        // A4 hotfix H1 (ruling R33): PDFBox 추출 잔여물 등으로 text에 0x00이 섞이면
        // Postgres가 "invalid byte sequence for encoding "UTF8": 0x00"로 배치 insert를 통째로 거부한다.
        // insertBatch의 안전망이 NUL만 제거하고 정상 저장해야 한다.
        List<Long> ids = repo.insertBatch(List.of(new EvidenceChunkRepository.ChunkRow(
                EvidenceKind.GUIDE, 1, "IT#nul1", "parent", null, true, "제목\u0000섹션", "타이틀\u0000",
                "본문 앞\u0000본문 뒤", Map.of(), null)));

        Map<Long, ChunkHit> found = repo.findParents(Set.of(ids.get(0)));
        assertThat(found).containsKey(ids.get(0));
        ChunkHit hit = found.get(ids.get(0));
        assertThat(hit.text()).doesNotContain("\u0000").isEqualTo("본문 앞본문 뒤");
        assertThat(hit.title()).doesNotContain("\u0000").isEqualTo("타이틀");
        assertThat(hit.sectionTitle()).doesNotContain("\u0000").isEqualTo("제목섹션");
    }

    // 최종 리뷰 F7: 업종 필터는 두 다리(벡터·키워드) 모두에 걸리고, 업종 필드가 없는 행은 통과시킨다
    // (사진이 있는 사고사망 사례는 원본에 업종이 없다 — R50/R51). 시드가 만들지 않는 kind(MSDS)로 격리한다.
    @Test
    void 업종_필터는_두_다리에_걸리고_업종_없는_행은_통과한다() {
        repo.insertBatch(List.of(
                new EvidenceChunkRepository.ChunkRow(EvidenceKind.MSDS, 1, "IT#biz-mfg", "child", null, true, "s", "업종시험 제조", "업종시험 본문 제조", Map.of("business", "제조업"), unit(5)),
                new EvidenceChunkRepository.ChunkRow(EvidenceKind.MSDS, 2, "IT#biz-con", "child", null, true, "s", "업종시험 건설", "업종시험 본문 건설", Map.of("business", "건설업"), unit(5)),
                new EvidenceChunkRepository.ChunkRow(EvidenceKind.MSDS, 3, "IT#biz-none", "child", null, true, "s", "업종시험 없음", "업종시험 본문 없음", Map.of(), unit(5))));
        Set<EvidenceKind> msds = Set.of(EvidenceKind.MSDS);
        String tsq = TsQueryBuilder.build("업종시험");

        assertThat(repo.vectorSearch(unit(5), msds, null, "제조업", 10)).extracting(ChunkHit::refKey)
                .contains("IT#biz-mfg", "IT#biz-none").doesNotContain("IT#biz-con");
        assertThat(repo.keywordSearch(tsq, msds, null, "제조업", 10)).extracting(ChunkHit::refKey)
                .contains("IT#biz-mfg", "IT#biz-none").doesNotContain("IT#biz-con");
        // 업종이 없으면 필터 없음
        assertThat(repo.vectorSearch(unit(5), msds, null, null, 10)).extracting(ChunkHit::refKey)
                .contains("IT#biz-mfg", "IT#biz-con", "IT#biz-none");
        assertThat(repo.keywordSearch(tsq, msds, null, null, 10)).extracting(ChunkHit::refKey)
                .contains("IT#biz-mfg", "IT#biz-con", "IT#biz-none");
    }

    @Test
    void deleteByKind는_해당_kind_행을_모두_지운다() {
        // F-IT1: 5433의 evidence_chunk는 이제 GUIDE/LAW/CASE_* 시드를 영구히 담고 있어
        // LAW로 하면 시드 행까지 함께 지워진다. 시드가 절대 만들지 않는 kind(MSDS)로 바꿔
        // "새로 넣은 1건 → countByKind에 MSDS가 없거나 0"을 확인한다.
        repo.insertBatch(List.of(new EvidenceChunkRepository.ChunkRow(EvidenceKind.MSDS, 1, "IT#msds1", "parent", null, true, "s", "MSDS", "MSDS 본문", Map.of(), null)));
        assertThat(repo.countByKind()).containsEntry("MSDS", 1L);

        repo.deleteByKind(EvidenceKind.MSDS);

        assertThat(repo.countByKind().getOrDefault("MSDS", 0L)).isEqualTo(0L);
    }
}
