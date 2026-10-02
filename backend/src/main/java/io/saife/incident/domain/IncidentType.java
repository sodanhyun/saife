package io.saife.incident.domain;

import io.saife.core.domain.AccidentType;

/**
 * 사고 발생형태. 한국산업안전보건공단 재해 발생형태 분류 중 제조업에서 주로 나오는 항목이다.
 *
 * <p>사진 판독의 6축({@link AccidentType})과 다르다. 6축은 "사진에 보이는 빠진 안전조치"의 축이고,
 * 이 값은 실제로 일어난 사고의 형태다. 사고 보고에서 "보호구 미착용"을 고를 수 없는 이유가 이것이다
 * (보호구 미착용은 원인이지 사고의 형태가 아니다).
 *
 * <p>{@link #axis()}는 같은 설비의 위험요인(6축)과 잇기 위한 대응이다. 대응하는 축이 없으면 null이고,
 * 그때는 사고 전 기록의 "같은 발생형태" 판단과 수시평가의 신규 위험요인 생성을 하지 않는다.
 * 코드 이름은 6축과 겹치는 것(FALL, CAUGHT, DROP, STRUCK, FIRE)을 그대로 써서 기존 기록과 호환한다.
 */
public enum IncidentType {
    FALL("떨어짐", "추락", AccidentType.FALL),
    TRIP("넘어짐", "넘어짐", null),
    CAUGHT("끼임", "협착", AccidentType.CAUGHT),
    STRUCK("부딪힘", "부딪힘", AccidentType.STRUCK),
    DROP("물체에 맞음", "낙하", AccidentType.DROP),
    CRUSHED("깔림", "깔림", null),
    OVERTURN("뒤집힘", "전도", null),
    COLLAPSE("무너짐", "붕괴", AccidentType.DROP),
    CUT("절단, 베임, 찔림", "절단", AccidentType.CAUGHT),
    ELECTRIC("감전", "감전", null),
    FIRE("화재", "화재", AccidentType.FIRE),
    EXPLOSION("폭발, 파열", "폭발", AccidentType.FIRE),
    CHEMICAL("화학물질 누출, 접촉", "화학물질", null),
    TEMPERATURE("이상온도 접촉", "화상", null),
    STRAIN("불균형 및 무리한 동작", "요통", null);

    private final String label;
    private final String searchTerm;
    private final AccidentType axis;

    IncidentType(String label, String searchTerm, AccidentType axis) {
        this.label = label;
        this.searchTerm = searchTerm;
        this.axis = axis;
    }

    public String getLabel() {
        return label;
    }

    /** 공단 사례 원문 검색어(옛 용어 포함) */
    public String getSearchTerm() {
        return searchTerm;
    }

    /** 위험요인 6축 대응. 없으면 null */
    public AccidentType axis() {
        return axis;
    }

    /** 6축 값으로 기록을 만들던 호출부용. 같은 이름의 사고 발생형태로 옮긴다(PPE는 대응 없음) */
    public static IncidentType fromAxis(AccidentType axis) {
        if (axis == null) {
            return null;
        }
        return switch (axis) {
            case FALL -> FALL;
            case CAUGHT -> CAUGHT;
            case DROP -> DROP;
            case STRUCK -> STRUCK;
            case FIRE -> FIRE;
            case PPE -> null;
        };
    }

    /** DB 값 해석. 모르는 값(예전 PPE 기록 등)은 null로 읽는다. 조회 전체가 깨지지 않게 한다 */
    public static IncidentType parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return IncidentType.valueOf(code.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
