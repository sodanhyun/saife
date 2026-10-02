package io.saife.evidence.search;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.EvidenceKind;
import java.util.Set;

/** 검색 요청. 종류별 팩토리(cases/guides/laws)가 kinds·rerank 기본값을 고정한다. photoOnly: 사진이 있는 사례만 */
public record SearchRequest(String query, Set<EvidenceKind> kinds, AccidentType accidentType, String business, int k, boolean rerank,
                            boolean photoOnly) {
    public SearchRequest(String query, Set<EvidenceKind> kinds, AccidentType accidentType, String business, int k, boolean rerank) {
        this(query, kinds, accidentType, business, k, rerank, false);
    }
    /** 사진이 있는 사고사망 사례만. 유사 재해사례에 현장 사진 1건을 넣을 때 쓴다 */
    public static SearchRequest casePhotos(String query, AccidentType axis, int k) {
        return new SearchRequest(query, Set.of(EvidenceKind.CASE_FATALITY), axis, null, k, true, true);
    }
    public static SearchRequest cases(String query, AccidentType axis, String business, int k) {
        return new SearchRequest(query, Set.of(EvidenceKind.CASE_FATALITY, EvidenceKind.CASE_DISASTER), axis, business, k, true);
    }
    public static SearchRequest guides(String query, int k) {
        return new SearchRequest(query, Set.of(EvidenceKind.GUIDE), null, null, k, true);
    }
    public static SearchRequest laws(String query, int k) {
        return new SearchRequest(query, Set.of(EvidenceKind.LAW), null, null, k, true);
    }
    public SearchRequest withoutRerank() { return new SearchRequest(query, kinds, accidentType, business, k, false, photoOnly); }
}
