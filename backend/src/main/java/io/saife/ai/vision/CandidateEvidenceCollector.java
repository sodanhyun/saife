package io.saife.ai.vision;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.evidence.service.LawArticleService;
import io.saife.evidence.service.LawCitationTable;
import io.saife.evidence.service.LawEvidenceBuilder;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사진 판독(UC1) 후보 1건에 붙는 근거 3장 — 지침 1 · 조문 1 · 사진 있는 사례 1.
 *
 * <p>번호 매김은 항상 지침(1) → 조문(2) → 사례(3) 순으로 고정한다. 심사위원이
 * "이 후보의 근거가 뭐냐"고 물었을 때 매번 같은 순서로 나와야 한다.
 *
 * <p>모델 호출이 없고 리랭크도 쓰지 않는다({@code withoutRerank()}) — 후보마다 즉석에서
 * 도는 소규모 검색이라 원장({@code EvidenceLedger})에 올리지 않고 영속화도 하지 않는다.
 * {@code VisionAssessmentService.persist()}와 {@code result()}가 매번 이 메서드를 다시
 * 불러 계산한다({@code CandidateEvidenceCollectorTest}).
 *
 * <p>각 다리(지침·조문·사례)는 독립된 try/catch로 감싼다. 한 다리가 실패해도 나머지
 * 근거는 그대로 나가야 판독 결과 화면이 통째로 비지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateEvidenceCollector {

    private static final String BUSINESS = "제조업";
    private static final int CASE_CANDIDATES = 3;

    private final EvidenceSearchService search;
    private final LawArticleService lawArticleService;

    public List<Evidence> forCandidate(AccidentType axis, String missingControl) {
        String q = buildQuery(axis, missingControl);
        List<Evidence> out = new ArrayList<>();

        try {
            search.search(SearchRequest.guides(q, 1).withoutRerank())
                    .stream().findFirst().ifPresent(out::add);
        } catch (Exception e) {
            log.warn("[UC1] 근거 — 지침 검색 실패: {}", e.getMessage());
        }

        try {
            List<LawCitationTable.Citation> citations = LawCitationTable.forAxis(axis);
            if (!citations.isEmpty()) {
                LawEvidenceBuilder.build(lawArticleService, citations.get(0))
                        .stream().findFirst().ifPresent(out::add);
            }
        } catch (Exception e) {
            log.warn("[UC1] 근거 — 조문 조회 실패: {}", e.getMessage());
        }

        try {
            List<Evidence> cases = search.search(
                    SearchRequest.cases(q, axis, BUSINESS, CASE_CANDIDATES).withoutRerank());
            // 사진 있는 사례를 우선한다 — 없으면 최상위 결과로 대체. 카드가 실제로 그리는 것은
            // 썸네일이므로 thumbnailUrl로 판정한다(매퍼가 mediaUrl과 함께 채우지만 기준을 맞춘다)
            cases.stream().filter(c -> c.thumbnailUrl() != null).findFirst()
                    .or(() -> cases.stream().findFirst())
                    .ifPresent(out::add);
        } catch (Exception e) {
            log.warn("[UC1] 근거 — 사례 검색 실패: {}", e.getMessage());
        }

        List<Evidence> numbered = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            numbered.add(out.get(i).withNo(i + 1));
        }
        return numbered;
    }

    /** R51 — 축 라벨 + 빠진 조치. 장소 태그는 절대 섞지 않는다 */
    private String buildQuery(AccidentType axis, String missingControl) {
        // 축이나 라벨이 비어 와도 NPE로 근거 전체를 잃지 않는다 — 빠진 조치 문구만으로 검색한다
        String label = axis == null ? "" : axis.getSearchTerm();
        String control = missingControl == null ? "" : missingControl.strip();
        if (control.isBlank()) {
            return label;
        }
        if (label.isEmpty()) {
            return control;
        }
        return control.contains(label) ? control : label + " " + control;
    }
}
