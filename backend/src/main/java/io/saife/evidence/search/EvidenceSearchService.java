package io.saife.evidence.search;

import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 하이브리드 검색 파이프라인(Inufleet AgenticRagService 이식).
 * 벡터 k×4 ‖ 키워드 k×4 → RRF → Parent 확장 → 리랭크 → 도메인 보정 → 카드.
 * 키가 없으면 키워드만으로 같은 형태의 결과(KEYWORD_FALLBACK). 어떤 다리가 실패해도
 * 사용자 질의에 예외를 던지지 않는다 — 각 하위 컴포넌트가 실패를 빈 결과로 흡수한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceSearchService {
    private final EvidenceChunkRepository repository;
    private final QueryEmbedder embedder;
    private final ParentChunkExpander expander;
    private final LlmReranker reranker;

    public List<Evidence> search(SearchRequest req) {
        if (req.query() == null || req.query().isBlank() || req.k() <= 0) return List.of();
        String axis = req.accidentType() == null ? null : req.accidentType().name();
        String tsquery = TsQueryBuilder.build(req.query());

        Optional<float[]> q = embedder.available() ? embedder.embed(req.query()) : Optional.empty();
        // 벡터도 못 쓰고 tsquery도 빈 토큰(예: 전부 1글자·구두점)이면 DB를 아예 건드리지 않는다
        if (q.isEmpty() && tsquery.isBlank()) return List.of();
        Origin origin = q.isPresent() ? Origin.CACHE : Origin.KEYWORD_FALLBACK;

        // H2: 사례(CASE) 검색은 두 kind를 한 SQL 조회에 같이 태우면 안 된다 — 아래
        // retrieveAndBoost/selectWithKindQuota 문서를 참고
        boolean caseQuota = req.kinds() != null && req.kinds().contains(EvidenceKind.CASE_FATALITY)
                && req.kinds().contains(EvidenceKind.CASE_DISASTER);
        if (!caseQuota) {
            List<ChunkHit> boosted = retrieveAndBoost(req, req.kinds(), req.k(), q, tsquery, axis);
            boolean reranked = req.rerank() && q.isPresent();
            List<ChunkHit> windowed = reranked ? boosted
                    : boosted.subList(0, Math.min(boosted.size(), req.k() * SearchPolicy.NO_RERANK_MULTIPLIER));
            return windowed.stream().limit(req.k()).map(h -> EvidenceMapper.toEvidence(h, origin)).toList();
        }

        // kind마다 완전히 독립된 검색·부스트를 돌린 뒤에야 합친다(아래 selectWithKindQuota 문서 참고)
        List<ChunkHit> fatality = retrieveAndBoost(req, Set.of(EvidenceKind.CASE_FATALITY), req.k(), q, tsquery, axis);
        List<ChunkHit> disaster = retrieveAndBoost(req, Set.of(EvidenceKind.CASE_DISASTER), req.k(), q, tsquery, axis);
        if (fatality.isEmpty() && disaster.isEmpty()) return List.of();
        List<ChunkHit> merged = new ArrayList<>(fatality);
        merged.addAll(disaster);
        List<ChunkHit> selected = selectWithKindQuota(merged, req.k());
        return selected.stream().map(h -> EvidenceMapper.toEvidence(h, origin)).toList();
    }

    /**
     * 벡터·키워드 검색 → RRF → parent 확장 → 리랭크(또는 정규화) → 도메인 보정까지의
     * 한 쪽(kind 집합 하나) 파이프라인. {@code search()}가 사례 검색이 아니면 한 번,
     * 사례 검색이면 kind별로 두 번 부른다.
     */
    private List<ChunkHit> retrieveAndBoost(SearchRequest req, Set<EvidenceKind> kinds, int k,
                                             Optional<float[]> q, String tsquery, String axis) {
        int wide = k * SearchPolicy.CANDIDATE_MULTIPLIER;
        // 최종 리뷰 F7: 업종 필터를 SQL 두 다리 모두에 건다(업종 필드가 없는 행은 통과 — 저장소 문서 참고)
        String business = req.business() == null || req.business().isBlank() ? null : req.business().strip();
        List<ChunkHit> vec = q.map(v -> repository.vectorSearch(v, kinds, axis, business, wide)).orElse(List.of());
        List<ChunkHit> kw = repository.keywordSearch(tsquery, kinds, axis, business, wide);

        List<ChunkHit> fused = HybridRrf.fuse(vec, kw, wide);
        if (fused.isEmpty()) return List.of();
        // RRF 점수는 리랭크 여부와 무관하게 <b>리랭크 전에</b> 0~1로 정규화한다. 정규화 없이 더하면
        // RRF 최대치(1/61 ≈ 0.016)가 보정치(최대 0.10)에 완전히 묻혀 데모 모드 정렬이 관련성이 아니라
        // 축·업종·사진 유무로만 결정된다(리뷰 F1). 리랭크 앞에서 해야 하는 이유(최종 리뷰 F2):
        // 리랭커의 "후보 ≤ k" 단락 경로와 두 폴백(파싱 실패·호출 실패)은 입력 점수를 그대로 돌려주므로,
        // 여기서 정규화하지 않으면 그 경로들만 "유사도 2%" 같은 비정규 RRF 값을 카드에 싣는다.
        // 리랭크가 성공하면 점수는 rerank/10(0~1)으로 덮어써진다.
        List<ChunkHit> normalized = normalize(expander.expand(fused));
        boolean reranked = req.rerank() && q.isPresent();
        // 최종 리뷰 F13: 같은 지침(guideNo)의 형제 절은 서로 다른 parent로 확장돼도 카드로는 1건이다.
        // 리랭커가 k건으로 자른 뒤에 중복을 걷으면 지침 카드가 k보다 적게 남는다 → 리랭크 전에 걷는다
        List<ChunkHit> scored = reranked ? reranker.rerank(req.query(), dedupeGuides(normalized), k) : normalized;
        // 보정 + 지침(guideNo) 중복 제거를 먼저 끝내고, 그 다음에야 후보 폭을 자른다.
        // 순서가 바뀌면(자르기 → 중복 제거) 상위권에 같은 지침이 몰려 있을 때 서로 다른
        // 지침이 잘려나가 k건보다 적게 반환될 수 있다(리뷰 M1)
        return boostAndDedupe(scored, req);
    }

    /**
     * H2 수정 — 사례 검색에서 CASE_FATALITY가 밀려나는 문제.
     *
     * <p>{@code CASE_FATALITY}(사진이 있는 유일한 종류, 384/2,946건)는 원본 데이터셋
     * 자체가 {@code business} 필드를 주지 않아 전부 null이라 제조업 가산점
     * ({@link SearchPolicy#BOOST_MANUFACTURING})을 받을 수 없다. 반면 {@code CASE_DISASTER}는
     * business가 있어 가산점을 받는다.
     *
     * <p><b>처음 시도한 수정(Java 쪽 boost 이후 자르기)은 실제 DB로는 안 통했다</b> —
     * {@code EvidenceChunkRepository.keywordSearch()}/{@code vectorSearch()}가 SQL
     * {@code order by score desc limit ?}로 이미 상위 {@code wide}(=k×4)건만 골라
     * 자바로 넘긴다. 위치 태그 질의("공장동 후면 차양부" 등)에서는 CASE_DISASTER의
     * 원문 관련성이 워낙 높아 CASE_FATALITY가 이 SQL LIMIT 단계에서부터 아예 후보군에
     * 들지 못했다(실측: 2026-09-29 evidence_smoke.py B11=0/33, 설비 6개 전부 재현,
     * task-5-report.md) — 이러면 자바 쪽에서 아무리 가산점을 조정해도 애초에 후보가
     * 없으니 복구가 불가능하다. 그래서 {@code search()}가 사례 검색일 때는 kind마다
     * {@link #retrieveAndBoost}를 완전히 독립적으로(각자 자기 몫의 SQL LIMIT·wide 후보를
     * 확보해) 돌리고, 그 결과만 여기서 합쳐 kind별 자리를 나눠 뽑는다.
     *
     * <p>{@code ceil(k/2)}는 CASE_FATALITY, 나머지는 CASE_DISASTER. 한쪽 후보가
     * 모자라면 남는 자리를 다른 쪽의 다음 순위 후보로 채운다(둘 다 부족하면 있는
     * 만큼만 반환 — 정상적인 소량 결과다). 인자로 받는 {@code merged}는 두 독립
     * 파이프라인의 결과를 이어붙인 것일 뿐이라 kind별 부분 순서(각자 점수 내림차순)는
     * 그대로 유지된다.
     *
     * <p>최종 순서는 kind 배정과 무관하게 <b>보정 점수 내림차순 → kind(동점이면 FATALITY
     * 먼저) → refId 오름차순</b>으로 다시 정렬한다 — 화면에 보이는 카드 순서가 "어느
     * 자리에 배정됐는가"가 아니라 실제 관련성을 반영하게 하기 위해서다.
     */
    private List<ChunkHit> selectWithKindQuota(List<ChunkHit> merged, int k) {
        int fatalityQuota = (k + 1) / 2;   // ceil(k/2)
        int disasterQuota = k - fatalityQuota;   // floor(k/2)
        List<ChunkHit> fatality = merged.stream().filter(h -> h.kind() == EvidenceKind.CASE_FATALITY).toList();
        List<ChunkHit> disaster = merged.stream().filter(h -> h.kind() == EvidenceKind.CASE_DISASTER).toList();

        int fPick = Math.min(fatalityQuota, fatality.size());
        int dPick = Math.min(disasterQuota, disaster.size());
        int shortfall = k - (fPick + dPick);
        if (shortfall > 0) {
            int extraF = Math.min(shortfall, fatality.size() - fPick);
            fPick += extraF;
            shortfall -= extraF;
            int extraD = Math.min(shortfall, disaster.size() - dPick);
            dPick += extraD;
        }

        List<ChunkHit> picked = new ArrayList<>(fatality.subList(0, fPick));
        picked.addAll(disaster.subList(0, dPick));
        return picked.stream()
                .sorted(Comparator.comparingDouble(ChunkHit::score).reversed()
                        .thenComparing((ChunkHit h) -> h.kind() == EvidenceKind.CASE_FATALITY ? 0 : 1)
                        .thenComparingLong(ChunkHit::refId))
                .toList();
    }

    /** RRF 전용 점수를 0~1로 정규화한다. 분모는 양쪽 레그 rank 0 문서의 이론상 최대치(RRF_MAX) */
    private List<ChunkHit> normalize(List<ChunkHit> hits) {
        return hits.stream().map(h -> h.withScore(Math.min(1.0, h.score() / SearchPolicy.RRF_MAX))).toList();
    }

    /** 같은 지침(guideNo)은 순서상 처음 것 1건만 남긴다(지침이 아닌 청크는 그대로) */
    static List<ChunkHit> dedupeGuides(List<ChunkHit> hits) {
        Set<String> seen = new HashSet<>();
        List<ChunkHit> out = new ArrayList<>(hits.size());
        for (ChunkHit h : hits) {
            if (h.kind() == EvidenceKind.GUIDE && !seen.add(String.valueOf(h.metadata().get("guideNo")))) continue;
            out.add(h);
        }
        return out;
    }

    /**
     * 같은 축 +0.05, 제조업 +0.03, 사진 +0.02(사례만). 같은 지침(guideNo)은 1건.
     * 보정 후 점수는 1.0을 넘을 수 있다 — <b>정렬용</b>이다. 화면·도구 줄에 나가는 "유사도"는
     * {@link EvidenceMapper}가 1.0으로 자른다(최종 리뷰 F2).
     */
    private List<ChunkHit> boostAndDedupe(List<ChunkHit> hits, SearchRequest req) {
        List<ChunkHit> boosted = new ArrayList<>();
        Set<String> seenGuide = new HashSet<>();
        for (ChunkHit h : hits) {
            Map<String, Object> m = h.metadata();
            if (h.kind() == EvidenceKind.GUIDE) {
                String g = String.valueOf(m.get("guideNo"));
                if (!seenGuide.add(g)) continue;
            }
            double s = h.score();
            if (req.accidentType() != null && req.accidentType().name().equals(m.get("accidentType"))) s += SearchPolicy.BOOST_SAME_AXIS;
            if ("제조업".equals(m.get("business"))) s += SearchPolicy.BOOST_MANUFACTURING;
            if (h.kind().isCase() && Boolean.TRUE.equals(m.get("hasImage"))) s += SearchPolicy.BOOST_HAS_IMAGE;
            boosted.add(h.withScore(s));
        }
        boosted.sort(Comparator.comparingDouble(ChunkHit::score).reversed());
        return boosted;
    }
}
