package io.saife.evidence.service;

import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.live.Fetched;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 법 조문 인용 한 건(항 여러 개 포함 가능)을 근거 카드 한 장으로 만든다.
 *
 * <p>도구 3({@code HazardAnalysisTools.analyzeHazards})과 사고 등록(UC2,
 * {@code IncidentEvidenceCollector})이 같은 조문 카드 모양을 쓴다. 갈라두면
 * 한쪽만 고친 뒤 다른 쪽이 잊혀지고 카드 레이아웃이 슬쩍 달라진다.
 *
 * <p>조문 조회 실패는 빈 리스트로 흡수한다 — 호출부가 예외 처리를 신경 쓰지 않아도 된다.
 */
@Slf4j
public final class LawEvidenceBuilder {
    private LawEvidenceBuilder() {}

    private static final int SNIPPET_CHARS = 200;

    public static List<Evidence> build(LawArticleService lawArticleService, LawCitationTable.Citation c) {
        Fetched<List<LawArticle>> f;
        try {
            f = lawArticleService.get(c.lawName(), c.articleNo(), c.articleSub());
        } catch (Exception e) {
            log.warn("[LAW] 조문 조회 실패 {} 제{}조: {}", c.lawName(), c.articleNo(), e.getMessage());
            return List.of();
        }
        if (f.isEmpty()) {
            return List.of();
        }
        LawArticle first = f.value().get(0);
        String text = f.value().stream().map(LawArticle::getText).collect(Collectors.joining("\n"));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lawName", c.lawName());
        meta.put("articleNo", c.articleNo());
        meta.put("articleSub", c.articleSub());
        meta.put("why", c.why());
        meta.put("effectiveOn", first.getEffectiveOn() == null ? null : first.getEffectiveOn().toString());
        meta.put("sourceUrl", first.getSourceUrl());
        meta.put("fullText", text);
        String snippet = text.length() > SNIPPET_CHARS ? text.substring(0, SNIPPET_CHARS) + "…" : text;
        return List.of(new Evidence(0, EvidenceKind.LAW, first.getId(),
                first.getLawId() + ":" + c.articleNo() + ":" + c.articleSub(),
                first.citation().replaceAll(" [①-⑳]$", "") + ": " + c.why(),
                snippet, first.getSourceUrl(), null, null, f.origin(), 1.0, f.fetchedAt(), meta));
    }
}
