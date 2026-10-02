package io.saife.core.action;

import io.saife.core.domain.AccidentType;

import java.util.List;

/**
 * 빠진 안전조치 → 감소대책 문안 고정 매핑.
 *
 * <p><b>모델을 부르지 않는다.</b> 룰 엔진처럼 Java 상수다. 같은 사진에서 같은 위험요인이 나오면
 * 같은 대책 문안이 나와야 재현되고, "대책도 지어낸 것이냐"는 질문에 "발생형태와 빠진 조치로
 * 정해진 표에서 나온다"고 답할 수 있다. 문안은 <b>초안</b>이고 사람이 고쳐서 등록한다.
 *
 * <p>대책마다 고시 제12조의 우선순위({@link ControlPriority})를 붙인다. 공학적 대책이 가능한 곳에
 * 보호구 지급만 적으면 감독에서 바로 지적된다. 그래서 사다리 작업의 1순위 문안은 "보호구 착용"이
 * 아니라 "이동식 비계로 작업발판 확보"다(안전보건규칙 제42조①, ④는 작업발판 설치가 곤란한 경우의 예외).
 *
 * <p>조문 번호와 제목은 원문 기준이다(안전보건규칙). 제목에 가운뎃점이 있는 원문은 쉼표로 옮겼다.
 */
public final class ActionSuggestionTable {

    private ActionSuggestionTable() {}

    public static final String RULES = "산업안전보건기준에 관한 규칙";

    /**
     * @param content  감소대책 문안 초안
     * @param lawRef   근거 조문 (예: "산업안전보건기준에 관한 규칙 제42조제4항")
     * @param lawTitle 조문 제목
     * @param guideRef KOSHA GUIDE 규정번호. 규칙에 정해진 지침, 없으면 후보 근거 카드의 지침
     * @param priority 감소대책 우선순위
     */
    public record Suggestion(String content, String lawRef, String lawTitle, String guideRef,
                             ControlPriority priority) {}

    /** 키워드(빠진 조치 문구에 포함, 공백 무시) → 문안 + 조문 + 우선순위 (+ 정해진 KOSHA GUIDE) */
    private record Rule(AccidentType axis, List<String> keywords, String content,
                        String article, String title, ControlPriority priority, String guide) {
        Rule(AccidentType axis, List<String> keywords, String content, String article, String title, ControlPriority priority) {
            this(axis, keywords, content, article, title, priority, null);
        }
    }

    /** 이동식 사다리의 사용에 관한 기술지원규정 */
    private static final String GUIDE_LADDER = "A-G-4-2025";
    /** 비계 구조 및 안전작업에 관한 기술지원규정 */
    private static final String GUIDE_SCAFFOLD = "D-C-7-2026";

    /** 위에서부터 처음 맞는 규칙을 쓴다. 구체적인 것이 위에 온다 */
    private static final List<Rule> RULES_TABLE = List.of(
            // 떨어짐. 이동식 사다리는 작업발판을 설치하기 곤란한 경우에만 쓴다(제42조④)
            new Rule(AccidentType.FALL, List.of("최상부", "디딤대", "맨위"),
                    "이동식 비계(안전난간) 또는 말비계로 작업발판 확보, 사다리 사용 시 최상부 발판 및 그 하단 디딤대 사용 금지",
                    "제42조제4항", "추락의 방지", ControlPriority.ENGINEERING, GUIDE_LADDER),
            new Rule(AccidentType.FALL, List.of("채광창", "선라이트", "썬라이트", "슬레이트"),
                    "채광창 덮개 또는 추락방호망 설치, 폭 30cm 이상 작업발판 확보",
                    "제45조", "지붕 위에서의 위험 방지", ControlPriority.ENGINEERING),
            new Rule(AccidentType.FALL, List.of("이동식비계", "바퀴", "브레이크"),
                    "이동식 비계 안전난간 설치, 바퀴 브레이크와 쐐기로 고정",
                    "제68조", "이동식비계", ControlPriority.ENGINEERING, GUIDE_SCAFFOLD),
            new Rule(AccidentType.FALL, List.of("사다리", "발판미확보", "작업발판미설치"),
                    "이동식 비계(안전난간) 또는 말비계로 작업발판 확보",
                    "제42조제1항", "추락의 방지", ControlPriority.ENGINEERING, GUIDE_SCAFFOLD),
            new Rule(AccidentType.FALL, List.of("부착설비", "앵커", "구명줄"),
                    "안전대 부착설비(앵커, 수직구명줄) 설치, 사용 전 고정 상태 확인",
                    "제44조", "안전대의 부착설비 등", ControlPriority.ENGINEERING),
            new Rule(AccidentType.FALL, List.of("개구부"),
                    "개구부 덮개 고정 설치와 개구부 표지 부착",
                    "제43조", "개구부 등의 방호 조치", ControlPriority.ENGINEERING),
            new Rule(AccidentType.FALL, List.of("난간"),
                    "작업발판 단부에 안전난간(상부, 중간 난간대, 발끝막이판) 설치",
                    "제13조", "안전난간의 구조 및 설치요건", ControlPriority.ENGINEERING),
            new Rule(AccidentType.FALL, List.of("방호망", "방망"),
                    "작업 위치 하부에 추락방호망 설치",
                    "제42조제2항", "추락의 방지", ControlPriority.ENGINEERING),
            new Rule(AccidentType.FALL, List.of(),
                    "작업발판, 안전난간, 추락방호망 중 현장에 맞는 떨어짐 방지 설비 설치",
                    "제42조", "추락의 방지", ControlPriority.ENGINEERING),

            // 보호구. 지급과 착용 확인을 같이 적는다. 지급만 하고 끝나는 대책이 제일 흔한 미이행이다
            new Rule(AccidentType.PPE, List.of("안전모"),
                    "안전모 지급, 착용 지도와 작업 전 착용 확인",
                    "제32조", "보호구의 지급 등", ControlPriority.PPE),
            new Rule(AccidentType.PPE, List.of("안전대", "안전그네", "하네스"),
                    "안전대(안전그네) 지급, 작업 전 부착설비 체결 확인",
                    "제32조", "보호구의 지급 등", ControlPriority.PPE),
            new Rule(AccidentType.PPE, List.of("방독", "호흡", "마스크"),
                    "방독마스크(유기가스용) 지급, 정화통 교체 주기 관리와 작업 전 착용 확인",
                    "제450조", "호흡용 보호구의 지급 등", ControlPriority.PPE),
            new Rule(AccidentType.PPE, List.of("보안경", "보안면", "안면"),
                    "보안경 지급, 착용 지도와 작업 전 착용 확인",
                    "제32조", "보호구의 지급 등", ControlPriority.PPE),
            new Rule(AccidentType.PPE, List.of("안전화"),
                    "안전화 지급, 착용 지도와 작업 전 착용 확인",
                    "제32조", "보호구의 지급 등", ControlPriority.PPE),
            new Rule(AccidentType.PPE, List.of(),
                    "작업에 맞는 보호구 지급, 착용 지도와 작업 전 착용 확인",
                    "제32조", "보호구의 지급 등", ControlPriority.PPE),

            // 물체에 맞음
            new Rule(AccidentType.DROP, List.of("해지장치", "훅"),
                    "훅 해지장치 교체, 인양 전 해지장치 작동 확인",
                    "제137조", "해지장치의 사용", ControlPriority.ENGINEERING),
            new Rule(AccidentType.DROP, List.of("줄걸이", "슬링", "와이어로프", "달기구"),
                    "줄걸이 용구(와이어로프, 슬링) 점검표 운영, 손상품 즉시 폐기",
                    "제163조", "와이어로프 등 달기구의 안전계수", ControlPriority.ADMINISTRATIVE),
            new Rule(AccidentType.DROP, List.of("인양물", "크레인", "하부출입"),
                    "인양물 하부 출입 통제, 신호수 지정",
                    "제146조", "크레인 작업 시의 조치", ControlPriority.ADMINISTRATIVE),
            new Rule(AccidentType.DROP, List.of("방지망"),
                    "작업 구역 하부에 낙하물 방지망 설치",
                    "제14조", "낙하물에 의한 위험의 방지", ControlPriority.ENGINEERING),
            new Rule(AccidentType.DROP, List.of("적재"),
                    "적재물 고정과 적재 높이 제한, 무너짐 방지 조치",
                    "제393조", "화물의 적재", ControlPriority.ENGINEERING),
            new Rule(AccidentType.DROP, List.of(),
                    "낙하물 방지망 설치와 하부 출입 통제",
                    "제14조", "낙하물에 의한 위험의 방지", ControlPriority.ENGINEERING),

            // 끼임. 프레스는 방호장치(제103조)가 기준이다
            new Rule(AccidentType.CAUGHT, List.of("프레스", "광전자", "양수조작", "방호장치"),
                    "프레스 방호장치(광전자식, 양수조작식) 설치와 작동 유지, 금형 교체 시 안전블록 사용",
                    "제103조", "프레스 등의 위험 방지", ControlPriority.ENGINEERING),
            new Rule(AccidentType.CAUGHT, List.of(),
                    "회전, 구동부에 방호덮개(울) 설치",
                    "제87조", "원동기, 회전축 등의 위험 방지", ControlPriority.ENGINEERING),

            // 부딪힘
            new Rule(AccidentType.STRUCK, List.of("통로", "적치"),
                    "통로 적치물 제거와 보행 통로 폭 확보",
                    "제22조", "통로의 설치", ControlPriority.ELIMINATION),
            new Rule(AccidentType.STRUCK, List.of(),
                    "차량 동선 구획선 도색과 보행자 통로 표시",
                    "제22조", "통로의 설치", ControlPriority.ADMINISTRATIVE),

            // 화재
            new Rule(AccidentType.FIRE, List.of("소화기", "소화"),
                    "작업 구역 소화기 비치와 위치 표지 부착",
                    "제243조", "소화설비", ControlPriority.ENGINEERING),
            new Rule(AccidentType.FIRE, List.of("화재감시자", "감시자"),
                    "용접, 용단 작업 시 화재감시자 지정 배치",
                    "제241조의2", "화재감시자", ControlPriority.ADMINISTRATIVE),
            new Rule(AccidentType.FIRE, List.of("화기", "가연", "불티"),
                    "화기 작업 반경 내 가연물 제거, 불티 비산 방지포 설치",
                    "제241조", "화재위험작업 시의 준수사항", ControlPriority.ELIMINATION),
            new Rule(AccidentType.FIRE, List.of(),
                    "환기 확보와 화기 작업 전 가연물 점검",
                    "제232조", "폭발 또는 화재 등의 예방", ControlPriority.ADMINISTRATIVE));

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
        for (Rule rule : RULES_TABLE) {
            if (rule.axis() != axis) {
                continue;
            }
            boolean matches = rule.keywords().isEmpty()
                    || rule.keywords().stream().anyMatch(control::contains);
            if (matches) {
                // 규칙에 정해진 지침이 있으면 그것을 쓴다(검색 상위 지침은 작업과 무관한 경우가 있다)
                String guide = rule.guide() != null ? rule.guide() : guideRef == null || guideRef.isBlank() ? null : guideRef;
                return new Suggestion(rule.content(), RULES + " " + rule.article(), rule.title(), guide, rule.priority());
            }
        }
        return null;
    }
}
