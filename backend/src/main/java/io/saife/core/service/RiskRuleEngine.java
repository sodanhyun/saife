package io.saife.core.service;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 위험성 등급 룰 엔진.
 *
 * <p><b>등급은 모델이 정하지 않는다.</b> AI는 위험요인 후보를 제안하고 문안을 쓸 뿐이고,
 * 등급은 여기서 결정론적으로 나온다. 무대에서 모델이 흔들려도 등급은 흔들리지 않아야 하고,
 * 심사위원이 "이 등급은 AI가 정한 겁니까"라고 물으면 이 표를 보여줄 수 있어야 한다.
 *
 * <p>모든 판정은 {@link Decision#ruleTrace()}에 근거 문자열을 남긴다.
 * 그 문자열이 화면에 그대로 뜬다.
 *
 * <p>되묻기 턴이 의미를 가지려면 <b>데이터 코어가 알 수 없는 값</b>이 등급을 바꿔야 한다.
 * 그래서 전환점은 {@code workHeight}다 — 오늘 작업의 파라미터라 대장에 없다.
 * {@code anchorInstalled}는 대장값을 미리 채워 확인만 받는다.
 */
@Component
@Slf4j
public class RiskRuleEngine {

    /** 산업안전보건기준에 관한 규칙상 안전대 부착설비가 요구되는 높이 */
    private static final double ANCHOR_REQUIRED_HEIGHT_M = 2.0;

    /**
     * @param riskLevel 결정된 등급
     * @param frequency 빈도 (1~3)
     * @param severity  강도 (1~3)
     * @param ruleTrace 판정 근거. 화면에 그대로 표시된다
     */
    public record Decision(RiskLevel riskLevel, short frequency, short severity, String ruleTrace) {}

    /**
     * 슬롯 값으로 등급을 판정한다.
     *
     * @param accidentType 발생형태 축
     * @param slots        되묻기 턴에서 채운 값들 (work_height, anchor_installed, product_name …)
     */
    public Decision decide(AccidentType accidentType, Map<String, String> slots) {
        Map<String, String> s = slots != null ? slots : Map.of();

        Decision decision = switch (accidentType) {
            case FALL -> decideFall(s);
            case CAUGHT -> decideCaught(s);
            case FIRE -> decideFire(s);
            case DROP -> fixed(RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "낙하 축 기본값: 적재물 낙하 가능 구역 → '중'");
            case STRUCK -> fixed(RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "부딪힘 축 기본값: 통로, 차량계 장비 동선 중첩 → '중'");
            case PPE -> fixed(RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "보호구 미착용 확인 → '중'. 상위 위험요인과 결합 시 상향");
        };

        log.debug("[RULE] {} → {} ({})", accidentType, decision.riskLevel(), decision.ruleTrace());
        return decision;
    }

    /**
     * 추락 축 — 되묻기 턴이 등급을 바꾸는 지점.
     *
     * <pre>
     * 작업높이 2m 초과 + 안전대 부착설비 없음 → '상'
     * 작업높이 2m 초과 + 안전대 부착설비 있음 → '중'
     * 작업높이 2m 이하                      → '중'
     * </pre>
     */
    private Decision decideFall(Map<String, String> slots) {
        Double height = parseHeight(slots.get(SlotKeys.WORK_HEIGHT));
        Boolean anchor = parseBoolean(slots.get(SlotKeys.ANCHOR_INSTALLED));

        if (height == null) {
            return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "작업높이 미확인 → 잠정 '중'. 높이 확인 후 재판정 필요");
        }

        if (height > ANCHOR_REQUIRED_HEIGHT_M) {
            if (Boolean.TRUE.equals(anchor)) {
                return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 3,
                        String.format("작업높이 %.1fm (2m 초과) + 안전대 부착설비 있음 → '중'", height));
            }
            return new Decision(RiskLevel.HIGH, (short) 3, (short) 3,
                    String.format("작업높이 %.1fm (2m 초과) + 안전대 부착설비 없음 → '상'", height));
        }

        return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 2,
                String.format("작업높이 %.1fm (2m 이하) → '중'", height));
    }

    /** 협착 축 — 방호덮개 유무만 본다. 동력 상태는 사진으로 알 수 없어 판정 대상이 아니다. */
    private Decision decideCaught(Map<String, String> slots) {
        Boolean guard = parseBoolean(slots.get(SlotKeys.GUARD_INSTALLED));
        if (Boolean.FALSE.equals(guard)) {
            return new Decision(RiskLevel.HIGH, (short) 3, (short) 3,
                    "회전, 구동부 방호덮개 미설치 → '상'");
        }
        if (Boolean.TRUE.equals(guard)) {
            return new Decision(RiskLevel.LOW, (short) 1, (short) 3,
                    "방호덮개 설치 확인 → '하'. 정비 시 LOTO 별도 필요");
        }
        return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 3,
                "방호덮개 유무 미확인 → 잠정 '중'");
    }

    /** 화재 축 — 유기용제 취급 여부가 전환점. UC3 도장 작업 시나리오가 여기로 온다. */
    private Decision decideFire(Map<String, String> slots) {
        Boolean solvent = parseBoolean(slots.get(SlotKeys.SOLVENT_USED));
        Boolean ignition = parseBoolean(slots.get(SlotKeys.IGNITION_NEARBY));

        // 작업자에게 "유기용제 쓰십니까"를 따로 묻지 않는다. 제품명에서 읽어낸다.
        // 이걸 안 하면 유성페인트 작업인데 화재 위험이 '하'로 나온다 (실측 버그).
        if (solvent == null) {
            solvent = inferSolventFromProduct(slots.get(SlotKeys.PRODUCT_NAME));
        }

        if (Boolean.TRUE.equals(solvent) && Boolean.TRUE.equals(ignition)) {
            return new Decision(RiskLevel.HIGH, (short) 3, (short) 3,
                    "유기용제 취급 + 인근 화기 작업 있음 → '상'");
        }
        if (Boolean.TRUE.equals(solvent)) {
            return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 3,
                    "유기용제 취급 (증기는 공기보다 무거워 저지대 축적) → '중'");
        }
        return new Decision(RiskLevel.LOW, (short) 1, (short) 2,
                "가연물, 점화원 특이사항 없음 → '하'");
    }

    /**
     * 사고가 실제로 발생한 뒤의 재평가 (수시평가).
     *
     * <p><b>빈도는 추정값이 아니라 사실이 된다.</b> 평가 당시 "거의 없다"고
     * 본 위험이 실현됐으므로 빈도를 최고로 올린다. 강도는 결과로 판단한다 —
     * 휴업 3일 이상은 산업재해조사표 대상이라 상한선을 같이 쓴다.
     *
     * <p>그래서 사고 후 재평가는 거의 항상 '상'이 된다. <b>그게 맞다.</b>
     * 사고가 난 위험요인의 등급이 그대로면 그 평가는 틀렸던 것이고,
     * 화면에는 등급이 바뀜 이유가 {@link Decision#ruleTrace()}로 남는다.
     *
     * @param before    사고 전 등급 (없으면 null)
     * @param leaveDays 휴업일수 (모르면 null)
     */
    public Decision reassessAfterIncident(RiskLevel before, Integer leaveDays) {
        short frequency = 3;
        short severity = (leaveDays != null && leaveDays >= 3) ? (short) 3 : (short) 2;

        RiskLevel level = severity == 3 ? RiskLevel.HIGH : RiskLevel.MEDIUM;

        String beforeText = before == null ? "평가 이력 없음" : "종전 '" + label(before) + "'";
        String severityText = (leaveDays != null && leaveDays >= 3)
                ? "휴업 " + leaveDays + "일 (3일 이상) → 강도 3"
                : (leaveDays != null ? "휴업 " + leaveDays + "일 → 강도 2" : "휴업일수 미확인 → 강도 2");

        String trace = "사고 발생으로 위험이 실현됨 → 빈도 3 (추정치 아님). "
                + severityText + ". " + beforeText + " → '" + label(level) + "'";

        return new Decision(level, frequency, severity, trace);
    }

    private String label(RiskLevel level) {
        return switch (level) {
            case HIGH -> "상";
            case MEDIUM -> "중";
            case LOW -> "하";
        };
    }

    private Decision fixed(RiskLevel level, short frequency, short severity, String trace) {
        return new Decision(level, frequency, severity, trace);
    }

    /**
     * 제품명에서 유기용제 취급 여부를 읽는다.
     *
     * <p>"유성페인트"·"시너"·"락카"는 유기용제다. 작업자에게 "유기용제 쓰십니까"를
     * 따로 묻는 건 질문 하나를 낭비하는 것이고, 현장에서는 그 용어 자체를 잘 안 쓴다.
     *
     * <p>수성이라고 명시하면 아니라고 판단한다. 아무것도 모르면 null을 돌려
     * "미확인"으로 둔다 — 없다고 단정하지 않는다.
     */
    private Boolean inferSolventFromProduct(String productName) {
        if (productName == null || productName.isBlank()) {
            return null;
        }
        String p = productName.trim();
        if (p.matches("(?s).*(수성|아크릴에멀젼|무용제).*")) {
            return Boolean.FALSE;
        }
        if (p.matches("(?s).*(유성|에나멜|락카|라카|우레탄|에폭시|시너|신너|희석|솔벤트|톨루엔|크실렌).*")) {
            return Boolean.TRUE;
        }
        return null;
    }

    /** "3", "3m", "약 3.5미터" 같은 자유 입력에서 숫자만 뽑는다 */
    private Double parseHeight(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9.]", "");
        if (digits.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 한국어 자유 답변을 boolean으로. 애매하면 null을 돌려 "미확인"으로 둔다 */
    private Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        if (v.matches(".*(있|설치|했|예|네|yes|true|o).*") && !v.matches(".*(없|미설치|안|아니|no|false).*")) {
            return Boolean.TRUE;
        }
        if (v.matches(".*(없|미설치|안|아니|no|false|x).*")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 슬롯 키 상수 — 룰 엔진과 되묻기 턴이 같은 이름을 써야 한다 */
    public static final class SlotKeys {
        public static final String WORK_HEIGHT = "work_height";
        public static final String ANCHOR_INSTALLED = "anchor_installed";
        public static final String GUARD_INSTALLED = "guard_installed";
        public static final String PRODUCT_NAME = "product_name";
        public static final String SOLVENT_USED = "solvent_used";
        public static final String IGNITION_NEARBY = "ignition_nearby";

        private SlotKeys() {}

        /** 화면 표시용 질문 문구 */
        public static Map<String, String> questions() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put(WORK_HEIGHT, "작업 높이는 대략 몇 m인가요?");
            m.put(ANCHOR_INSTALLED, "안전대 부착설비가 설치되어 있습니까?");
            m.put(GUARD_INSTALLED, "회전, 구동부에 방호덮개가 설치되어 있습니까?");
            m.put(PRODUCT_NAME, "사용하는 제품명이 무엇인가요? 유기용제 여부를 확인하겠습니다.");
            m.put(SOLVENT_USED, "유기용제를 취급합니까?");
            m.put(IGNITION_NEARBY, "작업 구역 인근에 화기 작업이 있습니까?");
            return m;
        }
    }
}
