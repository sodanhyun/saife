package io.saife.core.service;

import io.saife.core.domain.Equipment;
import io.saife.core.domain.Process;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.ProcessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 자연어를 설비·장소 레코드에 붙이는 매칭기.
 *
 * <p><b>false negative가 이 출품작에서 가장 비싼 버그다.</b> 매칭에 실패해 같은 설비가
 * 두 ID로 쪼개지면 "하나의 설비 ID 위에서 잇는다"는 논지 자체가 무너지고,
 * UC4 타임라인 뷰가 눈에 띄게 깨진다.
 *
 * <p>3단 방어:
 * <ol>
 *   <li>정규화 명칭 + 위치 태그 <b>완전일치</b> — 잡히면 절대 새로 만들지 않는다</li>
 *   <li>Jaro-Winkler <b>유사도 히트 시 생성하지 말고 되묻는다</b>
 *       ("혹시 이것입니까?"). 점수로 자동 선택하지 않는다</li>
 *   <li>DB 유니크 제약 {@code uq_equipment_identity} — 앱 로직만 믿지 않는다</li>
 * </ol>
 *
 * <p>잘못 붙은 설비 ID는 조용히 틀리고, 틀린 걸 나중에 알아차리기 어렵다.
 * 애매하면 사람에게 묻는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EquipmentMatcher {

    /** 이 점수 이상이면 "혹시 이것입니까?" 후보로 올린다 */
    private static final double SUGGEST_THRESHOLD = 0.82;

    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;
    private final JaroWinklerSimilarity similarity = new JaroWinklerSimilarity();

    /**
     * 매칭 결과.
     *
     * @param equipment  완전일치로 확정된 설비 (없으면 null)
     * @param process    확정되거나 추정된 공정/장소 (없으면 null)
     * @param candidates 유사도 후보. 비어 있지 않으면 <b>되물어야 한다</b>
     * @param unmatched  아무것도 못 찾음 → 미등록 설비 등록 플로우로
     */
    public record MatchResult(Equipment equipment,
                              Process process,
                              List<Candidate> candidates,
                              boolean unmatched) {

        public boolean isConfirmed() {
            return equipment != null;
        }

        public boolean needsConfirmation() {
            return equipment == null && !candidates.isEmpty();
        }
    }

    public record Candidate(Long equipmentId, String name, String locationTag, double score) {}

    @Transactional(readOnly = true)
    public MatchResult match(Long siteId, String rawQuery, String rawLocationTag) {
        String normalized = Equipment.normalize(rawQuery);

        // 1단 — 완전일치
        Optional<Equipment> exact = equipmentRepository.findExact(siteId, rawLocationTag, normalized);
        if (exact.isPresent()) {
            Equipment eq = exact.get();
            log.debug("[MATCH] 완전일치 equipmentId={} '{}'", eq.getId(), eq.getName());
            return new MatchResult(eq, findProcess(eq.getProcessId()), List.of(), false);
        }

        // 2단 — 유사도 후보
        String keyword = firstMeaningfulToken(rawQuery);
        List<Equipment> pool = keyword.isBlank()
                ? equipmentRepository.findBySiteId(siteId)
                : equipmentRepository.searchByKeyword(siteId, keyword);
        if (pool.isEmpty()) {
            pool = equipmentRepository.findBySiteId(siteId);
        }

        List<Candidate> candidates = pool.stream()
                .map(e -> new Candidate(e.getId(), e.getName(), e.getLocationTag(),
                        score(normalized, rawLocationTag, e)))
                .filter(c -> c.score() >= SUGGEST_THRESHOLD)
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .limit(3)
                .toList();

        if (!candidates.isEmpty()) {
            log.debug("[MATCH] 유사 후보 {}건 — 생성하지 않고 되묻는다", candidates.size());
            return new MatchResult(null, matchProcess(siteId, rawLocationTag), candidates, false);
        }

        // 3단 — 미등록
        log.debug("[MATCH] 미등록 '{}'", rawQuery);
        return new MatchResult(null, matchProcess(siteId, rawLocationTag), List.of(), true);
    }

    /**
     * 미등록 설비 등록.
     *
     * <p>도구가 아니라 <b>슬롯 완료 시 백엔드 부수효과</b>로 호출된다.
     * 7번째 도구를 만들지 않는다 — 트레이스 패널 6줄을 유지하기 위해서다.
     *
     * <p>유니크 제약 위반은 "이미 있다"는 뜻이므로 조회로 대체한다(경쟁 조건 방어).
     */
    @Transactional
    public Equipment register(Long siteId, Long processId, String name, String locationTag, String objectCode) {
        String normalized = Equipment.normalize(name);

        Optional<Equipment> existing = equipmentRepository.findExact(siteId, locationTag, normalized);
        if (existing.isPresent()) {
            log.info("[MATCH] 등록 시도했으나 이미 존재 equipmentId={}", existing.get().getId());
            return existing.get();
        }

        try {
            return equipmentRepository.save(Equipment.builder()
                    .siteId(siteId)
                    .processId(processId)
                    .name(name)
                    .normalizedName(normalized)
                    .locationTag(locationTag)
                    .objectCode(objectCode)
                    .build());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // uq_equipment_identity — 동시 등록. 이긴 쪽을 쓴다
            log.warn("[MATCH] 유니크 제약 충돌 → 기존 레코드 재조회");
            return equipmentRepository.findExact(siteId, locationTag, normalized)
                    .orElseThrow(() -> e);
        }
    }

    private double score(String normalizedQuery, String queryLocation, Equipment candidate) {
        double nameScore = nullSafe(similarity.apply(normalizedQuery, candidate.getNormalizedName()));
        if (queryLocation == null || queryLocation.isBlank() || candidate.getLocationTag() == null) {
            return nameScore;
        }
        double locScore = nullSafe(similarity.apply(
                Equipment.normalize(queryLocation), Equipment.normalize(candidate.getLocationTag())));
        // 위치가 같으면 같은 설비일 가능성이 크게 오른다
        return nameScore * 0.6 + locScore * 0.4;
    }

    private double nullSafe(Double d) {
        return d != null ? d : 0.0;
    }

    private Process matchProcess(Long siteId, String locationTag) {
        if (locationTag == null || locationTag.isBlank()) {
            return null;
        }
        List<Process> hits = processRepository.searchByKeyword(siteId, locationTag);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private Process findProcess(Long processId) {
        return processId == null ? null : processRepository.findById(processId).orElse(null);
    }

    /** "공장동 후면 차양부 이동식 사다리" 같은 입력에서 검색에 쓸 토큰 하나를 고른다 */
    private String firstMeaningfulToken(String raw) {
        if (raw == null) {
            return "";
        }
        return java.util.Arrays.stream(raw.trim().split("\\s+"))
                .filter(t -> t.length() >= 2)
                .max(Comparator.comparingInt(String::length))
                .orElse("");
    }
}
