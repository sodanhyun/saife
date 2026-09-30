package io.saife.core.service;

import io.saife.core.domain.Equipment;
import io.saife.core.domain.WorkProcess;
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

    /** 이 점수 이상이면 확정으로 본다. 매번 되물으면 대화가 늘어진다 */
    private static final double CONFIRM_THRESHOLD = 0.88;

    /** 이 점수 이상이면 "혹시 이것입니까?" 후보로 올린다 */
    private static final double SUGGEST_THRESHOLD = 0.72;

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
                              WorkProcess process,
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
        String query = rawQuery == null ? "" : rawQuery.trim();

        // 모델은 장소와 설비를 한 문장으로 넘긴다 ("공장동 후면 차양부 이동식 사다리").
        // 등록된 장소 태그를 먼저 떼어내고 남은 부분을 설비명으로 본다.
        WorkProcess process = resolveProcess(siteId, query, rawLocationTag);
        String locationTag = rawLocationTag != null && !rawLocationTag.isBlank()
                ? rawLocationTag
                : (process != null ? process.getLocationTag() : null);
        String equipmentPart = stripLocation(query, locationTag);

        // 1단 — 완전일치 (유니크 제약과 같은 규칙)
        Optional<Equipment> exact = equipmentRepository
                .findExact(siteId, locationTag, Equipment.normalize(equipmentPart));
        if (exact.isPresent()) {
            Equipment eq = exact.get();
            log.debug("[MATCH] 완전일치 equipmentId={} '{}'", eq.getId(), eq.getName());
            return new MatchResult(eq, findProcess(eq.getProcessId()), List.of(), false);
        }

        // 2단 — 유사도. 장소가 특정되면 그 장소의 설비만 본다
        List<Equipment> pool = poolFor(siteId, process);
        List<Candidate> scored = pool.stream()
                .map(e -> new Candidate(e.getId(), e.getName(), e.getLocationTag(),
                        score(equipmentPart, query, locationTag, e)))
                // 점수 동점이면 설비 id로 가른다. 정렬이 흔들리면 같은 입력에
                // 다른 설비가 붙고, 그 순간 설비 이력이 두 ID로 쪼개진다
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparingLong(Candidate::equipmentId))
                .toList();

        // 아주 높으면 확정으로 본다 — "이동식 사다리" vs "이동식 사다리 A"에서
        // 매번 되물으면 대화가 늘어지고, 모델이 같은 도구를 반복 호출한다
        if (!scored.isEmpty() && scored.get(0).score() >= CONFIRM_THRESHOLD) {
            Equipment eq = equipmentRepository.findById(scored.get(0).equipmentId()).orElse(null);
            if (eq != null) {
                log.debug("[MATCH] 고신뢰 매칭 equipmentId={} score={}", eq.getId(), scored.get(0).score());
                return new MatchResult(eq, findProcess(eq.getProcessId()), List.of(), false);
            }
        }

        List<Candidate> candidates = scored.stream()
                .filter(c -> c.score() >= SUGGEST_THRESHOLD)
                .limit(3)
                .toList();

        if (!candidates.isEmpty()) {
            log.debug("[MATCH] 유사 후보 {}건 — 생성하지 않고 되묻는다", candidates.size());
            return new MatchResult(null, process, candidates, false);
        }

        // 3단 — 미등록
        log.debug("[MATCH] 미등록 query='{}' 설비부='{}' 장소='{}'", query, equipmentPart, locationTag);
        return new MatchResult(null, process, List.of(), true);
    }

    /**
     * equipmentId로 직접 확정한다 — 자유 텍스트 매칭을 건너뛰는 단축 경로.
     *
     * <p>작업 신고 화면이 {@code ?equipmentId=}로 이미 설비를 골라 들어온 대화에서 쓴다.
     * "공장동 후면 차양부 천장 페인트 작업"처럼 같은 공정에 설비가 둘 이상이면
     * 자유 텍스트 유사도만으로는 못 가른다(2026-09-29 실측, Jaro-Winkler가 임계값
     * 미만으로 떨어져 unmatched가 된다) — 이미 화면에서 확정된 설비 문맥을
     * 매칭기의 한계 때문에 버리지 않는다.
     *
     * @return 설비가 존재하면 확정된 {@code MatchResult}, 없으면 {@code unmatched}
     */
    @Transactional(readOnly = true)
    public MatchResult matchById(Long equipmentId) {
        return equipmentRepository.findById(equipmentId)
                .map(eq -> new MatchResult(eq, findProcess(eq.getProcessId()), List.of(), false))
                .orElseGet(() -> new MatchResult(null, null, List.of(), true));
    }

    /** 질의 안에 등록된 장소 태그가 들어 있는지 본다 */
    private WorkProcess resolveProcess(Long siteId, String query, String rawLocationTag) {
        if (rawLocationTag != null && !rawLocationTag.isBlank()) {
            WorkProcess byTag = matchProcess(siteId, rawLocationTag);
            if (byTag != null) {
                return byTag;
            }
        }
        String normalizedQuery = Equipment.normalize(query);
        for (WorkProcess p : processRepository.findBySiteIdOrderByIdAsc(siteId)) {
            String tag = p.getLocationTag();
            if (tag != null && !tag.isBlank()
                    && normalizedQuery.contains(Equipment.normalize(tag))) {
                return p;
            }
        }
        return matchProcess(siteId, query);
    }

    /** 질의에서 장소 부분을 떼어낸 나머지 = 설비명 후보 */
    private String stripLocation(String query, String locationTag) {
        if (locationTag == null || locationTag.isBlank()) {
            return query;
        }
        String normQuery = Equipment.normalize(query);
        String normTag = Equipment.normalize(locationTag);
        if (!normQuery.contains(normTag)) {
            return query;
        }
        String remainder = normQuery.replace(normTag, "").trim();
        return remainder.isBlank() ? query : remainder;
    }

    /** 장소가 특정되면 그 장소의 설비로 좁힌다 */
    private List<Equipment> poolFor(Long siteId, WorkProcess process) {
        if (process != null) {
            List<Equipment> scoped = equipmentRepository.findBySiteIdOrderByIdAsc(siteId).stream()
                    .filter(e -> process.getId().equals(e.getProcessId()))
                    .toList();
            if (!scoped.isEmpty()) {
                return scoped;
            }
        }
        return equipmentRepository.findBySiteIdOrderByIdAsc(siteId);
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

    /**
     * 설비명 유사도를 낸다.
     *
     * <p>설비명 부분과의 유사도를 기본으로 하되, 전체 질의가 설비명을 통째로 포함하면
     * ("…이동식 사다리 A 점검") 강하게 올린다. 위치가 일치하면 추가 가산한다.
     */
    private double score(String equipmentPart, String fullQuery, String queryLocation, Equipment candidate) {
        String candName = candidate.getNormalizedName();
        double nameScore = nullSafe(similarity.apply(Equipment.normalize(equipmentPart), candName));

        // 질의가 설비명을 포함하면 사실상 확정이다
        String normFull = Equipment.normalize(fullQuery);
        if (!candName.isBlank() && normFull.contains(candName)) {
            nameScore = Math.max(nameScore, 0.95);
        }

        if (queryLocation == null || queryLocation.isBlank() || candidate.getLocationTag() == null) {
            return nameScore;
        }
        double locScore = nullSafe(similarity.apply(
                Equipment.normalize(queryLocation), Equipment.normalize(candidate.getLocationTag())));
        // 위치가 같으면 같은 설비일 가능성이 크게 오른다
        return nameScore * 0.75 + locScore * 0.25;
    }

    private double nullSafe(Double d) {
        return d != null ? d : 0.0;
    }

    private WorkProcess matchProcess(Long siteId, String locationTag) {
        if (locationTag == null || locationTag.isBlank()) {
            return null;
        }
        List<WorkProcess> hits = processRepository.searchByKeyword(siteId, locationTag);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private WorkProcess findProcess(Long processId) {
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
