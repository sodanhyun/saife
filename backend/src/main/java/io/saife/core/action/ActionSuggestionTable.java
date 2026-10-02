package io.saife.core.action;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.service.LawCitationTable;

import java.util.List;

/**
 * 빠진 안전조치 → 감소대책 문안 고정 매핑.
 *
 * <p><b>모델을 부르지 않는다.</b> 룰 엔진처럼 Java 상수다. 같은 사진에서 같은 후보가 나오면
 * 같은 대책 문안이 나와야 무대에서 재현되고, 심사위원이 "대책도 AI가 지어낸 것이냐"고
 * 물을 때 "축과 빠진 조치로 정해진 표에서 나온다"고 답할 수 있다.
 *
 * <p>문안은 <b>초안</b>이다. 화면에서 사람이 고쳐서 등록한다(작성 보조).
 *
 * <p>법 조문은 새로 지어내지 않고 {@link LawCitationTable}에 이미 있는 조문만 고른다.
 * 그 표의 조문 번호는 {@code LawArticleServiceIT}가 시드와 대조해 검증한다.
 */
public final class ActionSuggestionTable {

    private ActionSuggestionTable() {}

    /**
     * @param content  감소대책 문안 초안
     * @param lawRef   근거 조문 (예: "산업안전보건기준에 관한 규칙 제44조")
     * @param lawWhy   조문 제목 요약
     * @param guideRef KOSHA GUIDE 규정번호. 후보의 근거 카드에 지침이 있을 때만 채운다
     */
    public record Suggestion(String content, String lawRef, String lawWhy, String guideRef) {}

    /** 키워드(빠진 조치 문구에 포함) → 문안 + 조문 번호 */
    private record Rule(AccidentType axis, List<String> keywords, String content, int article) {}

    /** 위에서부터 처음 맞는 규칙을 쓴다. 구체적인 것이 위에 온다 */
    private static final List<Rule> RULES = List.of(
            // 추락 — 축 안에서 구체적인 조치부터
            new Rule(AccidentType.FALL, List.of("부착설비", "앵커", "구명줄"),
                    "안전대 부착설비(앵커, 수직구명줄) 설치 후 사용 전 고정 상태 확인", 44),
            new Rule(AccidentType.FALL, List.of("개구부"),
                    "개구부 덮개 고정 설치와 개구부 표지 부착", 43),
            new Rule(AccidentType.FALL, List.of("난간"),
                    "작업발판 단부에 안전난간(상부, 중간 난간대, 발끝막이판) 설치", 42),
            new Rule(AccidentType.FALL, List.of("방호망", "방망"),
                    "작업 위치 하부에 추락방호망 설치", 42),
            new Rule(AccidentType.FALL, List.of("사다리"),
                    "사다리 최상단 발판 작업 금지, 작업발판이 있는 작업대로 교체", 42),
            new Rule(AccidentType.FALL, List.of(),
                    "작업발판, 안전난간, 추락방호망 중 현장에 맞는 추락 방지 설비 설치", 42),

            // 보호구 — 지급과 착용 확인을 같이 적는다. 지급만 하고 끝나는 대책이 제일 흔한 미이행이다
            new Rule(AccidentType.PPE, List.of("안전모"),
                    "안전모 지급, 착용 지도와 작업 전 착용 확인", 32),
            new Rule(AccidentType.PPE, List.of("안전대", "안전그네", "하네스"),
                    "안전대(안전그네) 지급, 착용 지도와 작업 전 부착설비 체결 확인", 32),
            new Rule(AccidentType.PPE, List.of("보안경", "보안면", "안면"),
                    "보안경 지급, 착용 지도와 작업 전 착용 확인", 32),
            new Rule(AccidentType.PPE, List.of("안전화"),
                    "안전화 지급, 착용 지도와 작업 전 착용 확인", 32),
            new Rule(AccidentType.PPE, List.of(),
                    "작업에 맞는 보호구 지급, 착용 지도와 작업 전 착용 확인", 32),

            // 낙하
            new Rule(AccidentType.DROP, List.of("방지망"),
                    "작업 구역 하부에 낙하물 방지망 설치", 14),
            new Rule(AccidentType.DROP, List.of("적재"),
                    "적재물 고정과 적재 높이 제한, 붕괴 방지 조치", 173),
            new Rule(AccidentType.DROP, List.of(),
                    "낙하물 방지망 설치와 하부 출입 통제", 14),

            // 협착
            new Rule(AccidentType.CAUGHT, List.of(),
                    "회전, 구동부에 방호덮개(울) 설치", 87),

            // 부딪힘
            new Rule(AccidentType.STRUCK, List.of("통로"),
                    "통로 적치물 제거와 보행 통로 폭 확보", 172),
            new Rule(AccidentType.STRUCK, List.of(),
                    "차량 동선 구획선 도색과 보행자 통로 유도 표식 설치", 172),

            // 화재
            new Rule(AccidentType.FIRE, List.of("소화기"),
                    "작업 구역 내 소화기 비치와 위치 표지 부착", 241),
            new Rule(AccidentType.FIRE, List.of("화기", "가연"),
                    "화기 작업 반경 내 가연물 제거, 불티 비산 방지포 설치", 241),
            new Rule(AccidentType.FIRE, List.of(),
                    "개구부, 배기구 확보와 화기 작업 전 가연물 점검", 239));

    /**
     * 감소대책 초안을 낸다.
     *
     * @param guideRef 후보 근거 카드에 붙은 KOSHA GUIDE 번호. 없으면 null
     */
    public static Suggestion suggest(AccidentType axis, String missingControl, String guideRef) {
        if (axis == null) {
            return null;
        }
        String control = missingControl == null ? "" : missingControl.replaceAll("\\s+", "");
        for (Rule rule : RULES) {
            if (rule.axis() != axis) {
                continue;
            }
            boolean matches = rule.keywords().isEmpty()
                    || rule.keywords().stream().anyMatch(control::contains);
            if (matches) {
                return new Suggestion(rule.content(),
                        LawCitationTable.RULES + " 제" + rule.article() + "조",
                        lawWhy(axis, rule.article()),
                        guideRef == null || guideRef.isBlank() ? null : guideRef);
            }
        }
        return null;
    }

    /** 조문 제목 요약을 {@link LawCitationTable}에서 그대로 가져온다 */
    private static String lawWhy(AccidentType axis, int article) {
        return LawCitationTable.forAxis(axis).stream()
                .filter(c -> c.articleNo() == article)
                .map(LawCitationTable.Citation::why)
                // 화면에 그대로 뜨는 문구다. 대시·가운뎃점 구분자를 쉼표로 바꾼다
                .map(w -> w.replace(" — ", ", ").replace("·", ", "))
                .findFirst()
                .orElse(null);
    }
}
