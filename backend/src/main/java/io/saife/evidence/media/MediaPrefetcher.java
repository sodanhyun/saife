package io.saife.evidence.media;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Equipment;
import io.saife.core.domain.Hazard;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 무대 전 사진·지침 PDF 프리페치 — <b>시연이 실제로 띄우는 카드</b>부터 채운다(최종 리뷰 F6).
 *
 * <p>예전에는 {@code image_url}이 있는 {@code public_case}를 {@code findAll()} 순서대로 200건 받았다.
 * 그 200건은 시연 설비(고소작업대·이동식 사다리 등)가 띄우는 사례와 무관해, 무대에서 공단 포털이
 * 막히면 정작 보이는 사진 카드가 전부 아이콘으로 떨어졌다.
 *
 * <p>순서:
 * <ol>
 *   <li><b>시연 검색 재현</b> — 시드 설비마다 그 설비 위험요인의 발생형태 축으로, 도구·브리핑·UC1이 쓰는
 *       것과 같은 모양의 요청({@code SearchRequest.cases}: 축 라벨 + 설비명, 업종 제조업 → 없으면 업종 없이,
 *       리랭크/비리랭크 두 경로)과 {@code SearchRequest.guides}를 돌려, 나온 사례 사진·지침 PDF를 먼저 받는다.</li>
 *   <li><b>채우기</b> — 사진 상한({@value #PHOTO_LIMIT})까지 사진 있는 사례를 더 받는다. 업종이 제조업인
 *       사례가 먼저, 그다음 시드 위험요인 축에 속한 사례. 사진이 있는 사례는 전부 사고사망(1040)이라
 *       원본에 업종 필드가 없다(전부 null, R50/R51) — 그래서 축 기준 채우기가 실질적인 2순위다.</li>
 *   <li><b>지침 PDF</b> — 시연 검색에서 나온 지침 다음, 위험작업 키워드로 지침명을 검색해 상한
 *       ({@value #PDF_LIMIT})까지.</li>
 * </ol>
 *
 * <p>미디어는 언제나 <b>DB id·guideNo로 찾은 행의 URL</b>로만 받는다(클라이언트 URL을 받지 않는 원칙,
 * {@link MediaController}와 같은 캐시 키).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaPrefetcher {
    static final int PHOTO_LIMIT = 200;
    static final int PDF_LIMIT = 30;
    private static final int THUMB_WIDTH = 320;
    private static final String MANUFACTURING = "제조업";
    /** 지침 채우기 키워드 — UC1/UC3가 실제로 다루는 위험작업 축과 맞춘다 */
    static final List<String> GUIDE_KEYWORDS = List.of("사다리", "도장", "용접", "고소", "방호", "지게차", "크레인", "밀폐");

    public record Result(int photos, int pdfs, int demoPhotos, int demoPdfs) {}

    private final EquipmentRepository equipmentRepository;
    private final HazardRepository hazardRepository;
    private final EvidenceSearchService search;
    private final PublicCaseRepository publicCaseRepository;
    private final KoshaGuideRepository koshaGuideRepository;
    private final MediaCache mediaCache;

    public Result prefetchDemo() {
        Set<Long> caseIds = new LinkedHashSet<>();
        Set<Long> guideIds = new LinkedHashSet<>();
        Set<AccidentType> demoAxes = new LinkedHashSet<>();
        collectDemoEvidence(caseIds, guideIds, demoAxes);

        // 1) 시연 검색이 띄우는 사례 사진
        Set<Long> tried = new LinkedHashSet<>();
        int photos = 0;
        for (Long id : caseIds) {
            if (photos >= PHOTO_LIMIT) break;
            tried.add(id);
            PublicCase c = publicCaseRepository.findById(id).orElse(null);
            if (c != null && fetchPhoto(c)) photos++;
        }
        int demoPhotos = photos;

        // 2) 채우기 — 제조업 사례 먼저, 그다음 시드 위험요인 축
        if (photos < PHOTO_LIMIT) {
            List<PublicCase> withImage = publicCaseRepository.findAll().stream()
                    .filter(c -> c.getImageUrl() != null && !tried.contains(c.getId()))
                    .sorted(Comparator.comparing(PublicCase::getId))
                    .toList();
            List<PublicCase> ordered = new ArrayList<>();
            withImage.stream().filter(c -> MANUFACTURING.equals(c.getBusiness())).forEach(ordered::add);
            withImage.stream().filter(c -> !MANUFACTURING.equals(c.getBusiness()) && demoAxes.contains(c.getAccidentType()))
                    .forEach(ordered::add);
            for (PublicCase c : ordered) {
                if (photos >= PHOTO_LIMIT) break;
                if (fetchPhoto(c)) photos++;
            }
        }

        // 3) 지침 PDF — 시연 검색 결과 먼저, 그다음 키워드
        int pdfs = 0;
        Set<Long> triedGuides = new LinkedHashSet<>();
        for (Long id : guideIds) {
            if (pdfs >= PDF_LIMIT) break;
            triedGuides.add(id);
            KoshaGuide g = koshaGuideRepository.findById(id).orElse(null);
            if (g != null && fetchPdf(g)) pdfs++;
        }
        int demoPdfs = pdfs;
        outer:
        for (String kw : GUIDE_KEYWORDS) {
            int perKeyword = 0;
            for (KoshaGuide g : koshaGuideRepository.searchByName(kw)) {
                if (pdfs >= PDF_LIMIT) break outer;
                if (perKeyword >= 4) break;
                if (!triedGuides.add(g.getId())) continue;
                if (fetchPdf(g)) {
                    pdfs++;
                    perKeyword++;
                }
            }
        }
        log.info("[MEDIA] 프리페치 완료 photos={}(시연 검색 {}) pdfs={}(시연 검색 {}) axes={}",
                photos, demoPhotos, pdfs, demoPdfs, demoAxes);
        return new Result(photos, pdfs, demoPhotos, demoPdfs);
    }

    /** 시드 설비 × 그 설비 위험요인 축마다 도구와 같은 모양의 검색을 돌려 사례·지침 id를 모은다 */
    private void collectDemoEvidence(Set<Long> caseIds, Set<Long> guideIds, Set<AccidentType> demoAxes) {
        List<Equipment> equipments = equipmentRepository.findAll().stream()
                .sorted(Comparator.comparing(Equipment::getId)).toList();
        for (Equipment e : equipments) {
            Set<AccidentType> axes = new LinkedHashSet<>();
            for (Hazard h : hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(e.getId())) {
                if (h.getAccidentType() != null) axes.add(h.getAccidentType());
            }
            demoAxes.addAll(axes);
            for (AccidentType axis : axes) {
                // HazardAnalysisTools.searchCases·BriefingComposer의 대체 질의와 같은 모양: 축 라벨 + 설비명
                String q = axis.getLabel() + " " + e.getName();
                List<SearchRequest> requests = List.of(
                        SearchRequest.cases(q, axis, MANUFACTURING, 3),                    // 도구 4(리랭크)
                        SearchRequest.cases(q, axis, MANUFACTURING, 3).withoutRerank(),    // UC1·브리핑(비리랭크)
                        SearchRequest.cases(q, axis, null, 3),                             // 업종 없이 재시도 경로
                        SearchRequest.guides(q, 3));                                       // 도구 3 지침
                for (SearchRequest r : requests) {
                    for (Evidence ev : safeSearch(r)) {
                        if (ev.refId() == null) continue;
                        if (ev.kind().isCase() && ev.mediaUrl() != null) caseIds.add(ev.refId());
                        if (ev.kind() == EvidenceKind.GUIDE) guideIds.add(ev.refId());
                    }
                }
            }
        }
    }

    private List<Evidence> safeSearch(SearchRequest r) {
        try {
            return Objects.requireNonNullElse(search.search(r), List.of());
        } catch (Exception ex) {
            log.warn("[MEDIA] 프리페치용 검색 실패 query={}: {}", r.query(), ex.getMessage());
            return List.of();
        }
    }

    private boolean fetchPhoto(PublicCase c) {
        if (c.getImageUrl() == null) return false;
        return mediaCache.fetch("case/" + c.getId(), c.getImageUrl(), "png")
                .flatMap(p -> mediaCache.thumbnail(p, THUMB_WIDTH))
                .isPresent();
    }

    private boolean fetchPdf(KoshaGuide g) {
        // MediaController와 같은 캐시 키·같은 판정 — 형식이 어긋난 guideNo는 어차피 서빙되지 않으니 받지 않는다
        if (g.getFileDownloadUrl() == null || !MediaKeys.isValidGuideNo(g.getGuideNo())) return false;
        return mediaCache.fetch("guide/" + g.getGuideNo(), g.getFileDownloadUrl(), "pdf").isPresent();
    }
}
