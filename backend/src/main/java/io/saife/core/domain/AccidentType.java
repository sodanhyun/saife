package io.saife.core.domain;

/**
 * 발생형태 6축.
 *
 * 사고유형을 분류하는 게 아니라 <b>빠진 안전조치</b>를 탐지하는 축이다.
 * 각 축의 탐지 대상은 "사진에 물리적으로 있거나 없는 것"이어야 한다 —
 * 추론해야 아는 것(기계 동력 상태, 환기 적정성, 사람의 부재)은 축에 넣지 않는다.
 *
 * 축 선정 근거는 사고사망 API 실측 분포(n=900):
 * 추락 42% · 협착 14% · 낙하 14% · 부딪힘 6% · 화재 2%. 넘어짐(0.9%)은 제외했다.
 * 다만 이 API는 사망사고만 다루므로 빈도 근거는 산재통계로 보완한다.
 */
public enum AccidentType {

    // 화면 이름은 공단 현행 재해 발생형태 분류(떨어짐, 끼임, 물체에 맞음 …)를 따른다.
    // 공단 사례 원문은 옛 용어("[추락]")를 그대로 쓰므로 검색 질의에는 searchTerm을 쓴다.
    FALL("떨어짐", "추락", "작업발판, 안전난간, 개구부 덮개 미설치"),
    CAUGHT("끼임", "협착", "방호덮개 미설치"),
    DROP("물체에 맞음", "낙하", "적재 불량, 낙하물 방지망 미설치"),
    STRUCK("부딪힘", "부딪힘", "통로 미확보, 통로 표시 없음"),
    FIRE("화재", "화재", "점화원 관리 미흡, 환기 부족, 소화기 미비치"),
    PPE("보호구 미착용", "보호구", "안전모, 안전대, 보안경 미착용");

    private final String label;
    private final String searchTerm;
    private final String missingControlHint;

    AccidentType(String label, String searchTerm, String missingControlHint) {
        this.label = label;
        this.searchTerm = searchTerm;
        this.missingControlHint = missingControlHint;
    }

    /** 공단 사례 원문 검색용 옛 용어(추락, 협착, 낙하) */
    public String getSearchTerm() {
        return searchTerm;
    }

    public String getLabel() {
        return label;
    }

    /** 비전 프롬프트에 넣는 탐지 대상 설명 */
    public String getMissingControlHint() {
        return missingControlHint;
    }
}
