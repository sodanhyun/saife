package io.saife.evidence.service;

import io.saife.core.domain.AccidentType;
import java.util.List;

/**
 * 발생형태 → 법 조문 고정 매핑. 룰 엔진처럼 Java 상수다. 무대에서 재현 가능해야 한다.
 *
 * <p>조문 번호가 실제 시드에 존재하는지는 {@code LawArticleServiceIT}가 검증한다. 번호가 틀리면 테스트가 잡는다.
 * {@code why}는 조문 제목을 원문대로 쓰되(가운뎃점은 쉼표로), 필요하면 이 작업과의 관련을 짧게 덧붙인다.
 * 각 축의 앞 두 건이 위험요인 도출 결과에 붙으므로 가장 직접적인 조문을 앞에 둔다.
 */
public final class LawCitationTable {
    private LawCitationTable() {}

    public static final String ACT = "산업안전보건법";
    public static final String ENFORCEMENT_RULE = "산업안전보건법 시행규칙";
    public static final String RULES = "산업안전보건기준에 관한 규칙";

    /**
     * @param articleSub 가지 조문 번호(제37조의4면 4). 없으면 0
     * @param why        조문 제목(원문)과 짧은 관련 설명
     */
    public record Citation(String lawName, int articleNo, int articleSub, String why) {}

    public static List<Citation> forAxis(AccidentType axis) {
        return switch (axis) {
            case FALL -> List.of(
                    new Citation(RULES, 42, 0, "추락의 방지 (이동식 사다리 사용 기준 포함)"),
                    new Citation(RULES, 44, 0, "안전대의 부착설비 등"),
                    new Citation(RULES, 43, 0, "개구부 등의 방호 조치"),
                    new Citation(RULES, 186, 0, "고소작업대 설치 등의 조치"));
            case CAUGHT -> List.of(
                    new Citation(RULES, 87, 0, "원동기, 회전축 등의 위험 방지"),
                    new Citation(RULES, 92, 0, "정비 등의 작업 시의 운전정지 등"));
            case DROP -> List.of(
                    new Citation(RULES, 14, 0, "낙하물에 의한 위험의 방지"),
                    new Citation(RULES, 393, 0, "화물의 적재"));
            case STRUCK -> List.of(
                    new Citation(RULES, 22, 0, "통로의 설치"),
                    new Citation(RULES, 172, 0, "접촉의 방지"),
                    new Citation(RULES, 179, 0, "전조등 등의 설치"));
            case FIRE -> List.of(
                    new Citation(RULES, 232, 0, "폭발 또는 화재 등의 예방 (인화성 증기 환기)"),
                    new Citation(RULES, 239, 0, "위험물 등이 있는 장소에서 화기 등의 사용 금지"),
                    new Citation(RULES, 241, 0, "화재위험작업 시의 준수사항"),
                    new Citation(RULES, 243, 0, "소화설비"),
                    new Citation(RULES, 422, 0, "관리대상 유해물질과 관계되는 설비"));
            case PPE -> List.of(
                    new Citation(RULES, 32, 0, "보호구의 지급 등"),
                    new Citation(RULES, 450, 0, "호흡용 보호구의 지급 등"));
        };
    }

    /** 작업 전 점검에 공통으로 붙는다: 위험성평가 결과의 근로자 공유와 기록 */
    public static List<Citation> common() {
        return List.of(
                new Citation(ACT, 36, 0, "위험성평가의 실시 (제4항 근로자 공유)"),
                new Citation(ENFORCEMENT_RULE, 37, 4, "위험성평가 결과의 기록, 보존"));
    }

    /** 사고 후: 조사표 제출과 작업 재개 전 수시평가 */
    public static List<Citation> afterIncident() {
        return List.of(
                new Citation(ENFORCEMENT_RULE, 73, 0,
                        "산업재해 발생 보고 등 (사망 또는 3일 이상 휴업이 필요한 재해, 1개월 이내)"),
                new Citation(ENFORCEMENT_RULE, 37, 0,
                        "위험성평가의 방법, 절차 및 시기 (제2항제3호 재해 발생 작업 재개 전 수시평가, 고시 제15조제2항제5호)"));
    }
}
