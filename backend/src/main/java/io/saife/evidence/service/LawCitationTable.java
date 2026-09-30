package io.saife.evidence.service;

import io.saife.core.domain.AccidentType;
import java.util.List;

/**
 * 발생형태 → 법 조문 고정 매핑. 룰 엔진처럼 Java 상수다 — 무대에서 재현 가능해야 한다.
 *
 * <p>조문 번호가 실제 시드에 존재하는지는 {@code LawArticleServiceIT}가 검증한다. 번호가 틀리면 테스트가 잡는다.
 */
public final class LawCitationTable {
    private LawCitationTable() {}

    public static final String ACT = "산업안전보건법";
    public static final String ENFORCEMENT_RULE = "산업안전보건법 시행규칙";
    public static final String RULES = "산업안전보건기준에 관한 규칙";

    public record Citation(String lawName, int articleNo, int articleSub, String why) {}

    public static List<Citation> forAxis(AccidentType axis) {
        return switch (axis) {
            case FALL -> List.of(
                    new Citation(RULES, 42, 0, "추락의 방지 — 작업발판·안전난간·추락방호망"),
                    new Citation(RULES, 43, 0, "개구부 등의 방호 조치"),
                    new Citation(RULES, 44, 0, "안전대의 부착설비"));
            case CAUGHT -> List.of(
                    new Citation(RULES, 87, 0, "원동기·회전축 등의 위험 방지 — 덮개·울"),
                    new Citation(RULES, 92, 0, "정비 등의 작업 시의 운전정지"));
            case DROP -> List.of(
                    new Citation(RULES, 14, 0, "낙하물에 의한 위험의 방지"),
                    new Citation(RULES, 173, 0, "화물의 적재"));
            case STRUCK -> List.of(
                    new Citation(RULES, 172, 0, "접촉의 방지 — 차량계 하역운반기계"),
                    new Citation(RULES, 179, 0, "전조등 및 후미등"));
            case FIRE -> List.of(
                    new Citation(RULES, 239, 0, "위험물 등이 있는 장소에서 화기 등의 사용 금지"),
                    new Citation(RULES, 241, 0, "화재위험작업 시의 준수사항"),
                    new Citation(RULES, 422, 0, "관리대상 유해물질 취급 시 환기"));
            case PPE -> List.of(
                    new Citation(RULES, 32, 0, "보호구의 지급"));
        };
    }

    /** 위험성평가 기록 의무 — 모든 작업계획서에 붙는다 */
    public static List<Citation> common() {
        return List.of(
                new Citation(ACT, 36, 0, "위험성평가의 실시"),
                new Citation(ENFORCEMENT_RULE, 37, 0, "위험성평가 실시내용 및 결과의 기록·보존"));
    }

    /** 사고 후 — 조사표 제출과 수시평가 */
    public static List<Citation> afterIncident() {
        return List.of(
                new Citation(ENFORCEMENT_RULE, 73, 0, "산업재해 발생 보고 — 휴업 3일 이상 시 1개월 이내"),
                new Citation(ACT, 36, 0, "재해 발생 작업의 재개 전 수시평가"));
    }
}
