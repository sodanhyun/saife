package io.saife.evidence;

/** 근거 종류. 카드 레이아웃과 검색 필터가 이 값으로 갈린다 */
public enum EvidenceKind {
    CASE_FATALITY, CASE_DISASTER, GUIDE, LAW, MSDS;

    public boolean isCase() { return this == CASE_FATALITY || this == CASE_DISASTER; }
}
