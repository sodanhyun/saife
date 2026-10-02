package io.saife.core.service;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 위험성 등급 룰 엔진.
 *
 * <p><b>등급은 모델이 정하지 않는다.</b> AI는 위험요인 후보를 제안하고 문안을 쓸 뿐이고,
 * 등급은 여기서 결정론적으로 나온다. 심사위원이 "이 등급은 AI가 정한 겁니까"라고 물으면
 * 이 표를 보여줄 수 있어야 한다.
 *
 * <p>모든 판정은 {@link Decision#ruleTrace()}에 근거 문자열을 남긴다. 그 문자열이 화면과
 * 서식에 그대로 뜨므로 <b>평문</b>으로 쓴다(화살표, 따옴표 등급, 빈도와 강도 숫자를 넣지 않는다).
 * 등급은 화면이 배지로 따로 그린다.
 *
 * <p>떨어짐 판정은 설비 종류마다 기준 조문이 다르다.
 * <ul>
 *   <li>이동식 사다리: 안전보건규칙 제42조제4항(3.5m 이하, 최상부 발판과 그 하단 디딤대 사용 금지,
 *       넘어짐 방지, 2m 이상이면 안전모와 안전대)</li>
 *   <li>고소작업대: 제186조(작업대 안전난간)</li>
 *   <li>그 밖의 고소작업: 제42조, 제44조(2m 이상 안전대 부착설비)</li>
 * </ul>
 * 높이 기준은 모두 "2m 이상"이다.
 */
@Component
@Slf4j
public class RiskRuleEngine {

    /** 안전모, 안전대, 안전대 부착설비가 요구되는 높이(이상) */
    static final double HEIGHT_2M = 2.0;
    /** 이동식 사다리를 쓸 수 있는 발판 높이 상한(이하) */
    static final double LADDER_MAX_M = 3.5;

    /** 설비 종류(파생 슬롯 {@link SlotKeys#EQUIPMENT_KIND}의 값) */
    public static final String KIND_LADDER = "LADDER";
    public static final String KIND_AERIAL_PLATFORM = "AERIAL_PLATFORM";

    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:m|M|미터)");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /**
     * @param riskLevel 결정된 등급
     * @param frequency 빈도 (1~3). DB 기록용이며 화면에 숫자로 쓰지 않는다
     * @param severity  강도 (1~3). DB 기록용이며 화면에 숫자로 쓰지 않는다
     * @param ruleTrace 판정 근거. 화면에 그대로 표시된다
     */
    public record Decision(RiskLevel riskLevel, short frequency, short severity, String ruleTrace) {}

    /**
     * 슬롯 값으로 등급을 판정한다.
     *
     * @param accidentType 발생형태 축
     * @param slots        되묻기 턴에서 채운 값들. {@link SlotKeys#EQUIPMENT_KIND}가 있으면 설비별 기준을 쓴다
     */
    public Decision decide(AccidentType accidentType, Map<String, String> slots) {
        Map<String, String> s = slots != null ? slots : Map.of();

        Decision decision = switch (accidentType) {
            case FALL -> decideFall(s);
            case CAUGHT -> decideCaught(s);
            case FIRE -> decideFire(s);
            case DROP -> new Decision(RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "적재물 낙하 가능 구역 (제14조, 제393조)");
            case STRUCK -> new Decision(RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "통로와 차량 동선 중첩 (제22조, 제172조)");
            case PPE -> decidePpe(s);
        };

        log.debug("[RULE] {} {} ({})", accidentType, decision.riskLevel(), decision.ruleTrace());
        return decision;
    }

    /**
     * 판정이 상일 때 먼저 검토할 감소대책 한 줄(고시 제12조 우선순위: 제거, 공학, 관리, 보호구).
     * 해당 없으면 null.
     */
    public String recommendation(AccidentType accidentType, Map<String, String> slots, Decision decision) {
        if (decision == null || decision.riskLevel() != RiskLevel.HIGH) {
            return null;
        }
        Map<String, String> s = slots != null ? slots : Map.of();
        return switch (accidentType) {
            case FALL -> switch (kindOf(s)) {
                case KIND_LADDER -> "이동식 비계(안전난간) 또는 말비계로 작업발판 확보 (제42조①)";
                case KIND_AERIAL_PLATFORM -> "작업대 안전난간 보수 후 작업 (제186조)";
                default -> "작업발판과 안전난간 설치, 곤란하면 안전대 부착설비 설치 (제42조, 제44조)";
            };
            case CAUGHT -> "방호덮개 설치, 정비 시 운전정지 (제87조, 제92조)";
            case FIRE -> "화기 작업 분리, 소화설비 비치 (제241조, 제243조)";
            default -> null;
        };
    }

    // ── 떨어짐 ───────────────────────────────────────────────────────

    private Decision decideFall(Map<String, String> slots) {
        Double height = parseHeight(slots.get(SlotKeys.WORK_HEIGHT));
        return switch (kindOf(slots)) {
            case KIND_LADDER -> decideLadder(height,
                    parseTopStep(slots.get(SlotKeys.TOP_STEP)),
                    parseBoolean(slots.get(SlotKeys.TIP_GUARD)));
            case KIND_AERIAL_PLATFORM -> decideAerialPlatform(height,
                    parseBoolean(slots.get(SlotKeys.PLATFORM_GUARDRAIL)));
            default -> decideGeneralHeight(height, parseBoolean(slots.get(SlotKeys.ANCHOR_INSTALLED)));
        };
    }

    /**
     * 이동식 사다리 (안전보건규칙 제42조제4항).
     *
     * <pre>
     * 발판 높이 3.5m 초과                         상 (사다리 사용 불가)
     * 2m 이상 + 최상부 발판 또는 그 하단 디딤대 사용   상
     * 2m 이상 + 넘어짐 방지 없음                    상
     * 2m 이상 + 조건 충족                          중 (안전모, 안전대 착용)
     * 2m 미만                                    하
     * </pre>
     */
    private Decision decideLadder(Double height, Boolean topStep, Boolean tipGuard) {
        if (height == null) {
            return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 2, "발판 높이 미확인, 확인 후 판정");
        }
        String h = meters(height);
        if (height > LADDER_MAX_M) {
            return new Decision(RiskLevel.HIGH, (short) 3, (short) 3,
                    "발판 높이 %s, 3.5m 초과로 이동식 사다리 사용 불가 (제42조④)".formatted(h));
        }
        if (height >= HEIGHT_2M) {
            List<String> violations = new ArrayList<>();
            if (Boolean.TRUE.equals(topStep)) {
                violations.add("최상부 발판 또는 그 하단 디딤대 사용");
            }
            if (Boolean.FALSE.equals(tipGuard)) {
                violations.add("넘어짐 방지(아웃트리거, 고정, 지지자) 없음");
            }
            if (!violations.isEmpty()) {
                return new Decision(RiskLevel.HIGH, (short) 3, (short) 3,
                        "발판 높이 %s, %s (제42조④)".formatted(h, String.join(", ", violations)));
            }
            if (topStep == null || tipGuard == null) {
                return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 3,
                        "발판 높이 %s, 디딤대 위치와 넘어짐 방지 확인 필요 (제42조④)".formatted(h));
            }
            return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 3,
                    "발판 높이 %s, 안전모, 안전대 착용 (제32조, 제42조④)".formatted(h));
        }
        return new Decision(RiskLevel.LOW, (short) 1, (short) 2,
                "발판 높이 %s (2m 미만), 사용 전 점검 (제42조④)".formatted(h));
    }

    /** 고소작업대 (안전보건규칙 제186조): 작업대 안전난간이 전환점이다 */
    private Decision decideAerialPlatform(Double height, Boolean guardrail) {
        if (Boolean.FALSE.equals(guardrail)) {
            return new Decision(RiskLevel.HIGH, (short) 3, (short) 3, "작업대 안전난간 일부 결손 (제186조)");
        }
        if (height == null) {
            return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 2, "작업대 높이 미확인, 확인 후 판정");
        }
        if (height >= HEIGHT_2M) {
            return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 3,
                    "작업대 높이 %s, 안전난간 확인, 안전대 착용 (제186조, 제32조)".formatted(meters(height)));
        }
        return new Decision(RiskLevel.LOW, (short) 1, (short) 2,
                "작업대 높이 %s (2m 미만)".formatted(meters(height)));
    }

    /** 그 밖의 고소작업 (제42조, 제44조): 2m 이상이면 안전대 부착설비 */
    private Decision decideGeneralHeight(Double height, Boolean anchor) {
        if (height == null) {
            return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 2, "작업 높이 미확인, 확인 후 판정");
        }
        String h = meters(height);
        if (height >= HEIGHT_2M) {
            if (Boolean.TRUE.equals(anchor)) {
                return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 3,
                        "작업 높이 %s (2m 이상), 안전대 착용 (제32조, 제44조)".formatted(h));
            }
            return new Decision(RiskLevel.HIGH, (short) 3, (short) 3,
                    "작업 높이 %s (2m 이상), 안전대 부착설비 없음 (제44조)".formatted(h));
        }
        return new Decision(RiskLevel.LOW, (short) 1, (short) 2, "작업 높이 %s (2m 미만)".formatted(h));
    }

    // ── 끼임, 화재, 보호구 ─────────────────────────────────────────────

    /** 끼임: 방호덮개 유무만 본다. 동력 상태는 사진으로 알 수 없어 판정 대상이 아니다 */
    private Decision decideCaught(Map<String, String> slots) {
        Boolean guard = parseBoolean(slots.get(SlotKeys.GUARD_INSTALLED));
        if (Boolean.FALSE.equals(guard)) {
            return new Decision(RiskLevel.HIGH, (short) 3, (short) 3, "회전, 구동부 방호덮개 없음 (제87조)");
        }
        if (Boolean.TRUE.equals(guard)) {
            return new Decision(RiskLevel.LOW, (short) 1, (short) 3, "방호덮개 설치, 정비 시 운전정지 (제92조)");
        }
        return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 3, "방호덮개 유무 미확인, 확인 후 판정");
    }

    /** 화재: 유기용제 취급 여부가 전환점. 작업자에게 따로 묻지 않고 제품명에서 읽는다 */
    private Decision decideFire(Map<String, String> slots) {
        Boolean solvent = solventOf(slots);
        Boolean ignition = parseBoolean(slots.get(SlotKeys.IGNITION_NEARBY));

        if (Boolean.TRUE.equals(solvent) && Boolean.TRUE.equals(ignition)) {
            return new Decision(RiskLevel.HIGH, (short) 3, (short) 3, "인화성 증기와 인근 화기 작업 (제239조, 제241조)");
        }
        if (Boolean.TRUE.equals(solvent)) {
            return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 3,
                    "인화성 증기, 점화원 관리, 실내면 환기와 방독마스크 (제232조, 제450조)");
        }
        return new Decision(RiskLevel.LOW, (short) 1, (short) 2, "인화성 물질과 점화원 특이사항 없음");
    }

    /** 보호구: 오늘 작업 조건에서 필요한 보호구를 그대로 적는다 */
    private Decision decidePpe(Map<String, String> slots) {
        List<String> items = new ArrayList<>();
        Double height = parseHeight(slots.get(SlotKeys.WORK_HEIGHT));
        if (height != null && height >= HEIGHT_2M) {
            items.add("안전모, 안전대");
        }
        if (Boolean.TRUE.equals(solventOf(slots))) {
            items.add("방독마스크");
        }
        String trace = items.isEmpty()
                ? "작업에 맞는 보호구 착용 확인 (제32조)"
                : String.join(", ", items) + " 착용 (제32조)";
        return new Decision(RiskLevel.MEDIUM, (short) 2, (short) 2, trace);
    }

    /**
     * 사고가 실제로 발생한 뒤의 재평가 (수시평가).
     *
     * <p>빈도는 추정값이 아니라 사실이 된다. 평가 당시 낮게 본 위험이 실현됐으므로 빈도를 최고로 올린다.
     * 강도는 결과로 판단한다. 휴업 3일 이상은 산업재해조사표 대상이라 상한선을 같이 쓴다.
     *
     * @param before    사고 전 등급 (없으면 null)
     * @param leaveDays 휴업일수 (모르면 null)
     */
    public Decision reassessAfterIncident(RiskLevel before, Integer leaveDays) {
        short frequency = 3;
        boolean serious = leaveDays != null && leaveDays >= 3;
        short severity = serious ? (short) 3 : (short) 2;
        RiskLevel level = serious ? RiskLevel.HIGH : RiskLevel.MEDIUM;

        String leave = leaveDays == null ? "휴업일수 미확인"
                : serious ? "휴업 %d일 (3일 이상)".formatted(leaveDays) : "휴업 %d일".formatted(leaveDays);
        String history = before == null ? ", 사고 전 평가 없음" : "";
        return new Decision(level, frequency, severity, "사고 발생으로 위험 실현, " + leave + history);
    }

    // ── 설비 종류 ─────────────────────────────────────────────────────

    /**
     * 설비명과 작업명에서 설비 종류를 고른다. 판정 기준 조문이 설비마다 달라서다.
     * 모르면 null(일반 고소작업 기준).
     */
    public static String equipmentKind(String equipmentName, String workName) {
        String text = (equipmentName == null ? "" : equipmentName) + " " + (workName == null ? "" : workName);
        if (text.contains("고소작업대")) {
            return KIND_AERIAL_PLATFORM;
        }
        if (text.contains("사다리")) {
            return KIND_LADDER;
        }
        return null;
    }

    /** 슬롯 맵에 설비 종류를 덧붙인 사본. 원본은 바꾸지 않는다 */
    public static Map<String, String> withEquipmentKind(Map<String, String> slots, String equipmentName, String workName) {
        Map<String, String> out = new HashMap<>(slots == null ? Map.of() : slots);
        String kind = equipmentKind(equipmentName, workName);
        if (kind != null) {
            out.putIfAbsent(SlotKeys.EQUIPMENT_KIND, kind);
        }
        return out;
    }

    /** 사다리 슬롯이 이미 있으면 설비 종류를 몰라도 사다리 기준으로 본다 */
    private static String kindOf(Map<String, String> slots) {
        String kind = slots.get(SlotKeys.EQUIPMENT_KIND);
        if (kind != null) {
            return kind;
        }
        if (slots.containsKey(SlotKeys.TOP_STEP) || slots.containsKey(SlotKeys.TIP_GUARD)) {
            return KIND_LADDER;
        }
        if (slots.containsKey(SlotKeys.PLATFORM_GUARDRAIL)) {
            return KIND_AERIAL_PLATFORM;
        }
        return "";
    }

    // ── 값 해석 ───────────────────────────────────────────────────────

    private Boolean solventOf(Map<String, String> slots) {
        Boolean solvent = parseBoolean(slots.get(SlotKeys.SOLVENT_USED));
        return solvent != null ? solvent : inferSolventFromProduct(slots.get(SlotKeys.PRODUCT_NAME));
    }

    /**
     * 제품명에서 유기용제 취급 여부를 읽는다. 유성페인트, 시너, 락카는 유기용제다.
     * 수성이라고 명시하면 아니라고 본다. 모르면 null(없다고 단정하지 않는다).
     */
    static Boolean inferSolventFromProduct(String productName) {
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

    /** 3.2를 "3.2m", 3.0을 "3m"로 */
    static String meters(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + "m";
    }

    /**
     * "3.2m요", "약 3.5미터", "사다리 6단, 3.2m" 같은 자유 입력에서 높이만 뽑는다.
     * 단위가 붙은 숫자를 먼저 보고, 없으면 첫 숫자를 쓴다.
     */
    static Double parseHeight(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher withUnit = NUMBER_WITH_UNIT.matcher(raw);
        Matcher any = NUMBER.matcher(raw);
        String digits = withUnit.find() ? withUnit.group(1) : (any.find() ? any.group(1) : null);
        if (digits == null) {
            return null;
        }
        try {
            return Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 최상부 발판이나 그 바로 아래 디딤대에 올라서는지. "맨 위 바로 아래 칸까지 올라가요"는 참이다.
     * 애매하면 null.
     */
    static Boolean parseTopStep(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        if (v.matches("(?s).*(아니|안 ?올라|않|중간|낮은|no|false).*")) {
            return Boolean.FALSE;
        }
        if (v.matches("(?s).*(맨 ?위|최상|꼭대기|바로 ?아래|하단|위 ?칸|올라|예|네|yes|true).*")) {
            return Boolean.TRUE;
        }
        return null;
    }

    /** 한국어 자유 답변을 boolean으로. 애매하면 null을 돌려 "미확인"으로 둔다 */
    static Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        boolean negative = v.matches("(?s).*(없|미설치|안 |안했|아니|no|false).*");
        if (!negative && v.matches("(?s).*(있|설치|했|예|네|잡아|고정|yes|true).*")) {
            return Boolean.TRUE;
        }
        if (negative) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 슬롯 키 상수. 룰 엔진과 되묻기 턴이 같은 이름을 써야 한다 */
    public static final class SlotKeys {
        public static final String WORK_HEIGHT = "work_height";
        /** 이동식 사다리: 최상부 발판 또는 그 하단 디딤대에 올라서는지 */
        public static final String TOP_STEP = "top_step";
        /** 이동식 사다리: 넘어짐 방지(아웃트리거, 고정, 지지자) 중 하나라도 있는지 */
        public static final String TIP_GUARD = "tip_guard";
        /** 고소작업대: 작업대 안전난간이 온전한지 */
        public static final String PLATFORM_GUARDRAIL = "platform_guardrail";
        /** 그 밖의 고소작업: 안전대 부착설비. 사다리 흐름에서는 쓰지 않는다 */
        public static final String ANCHOR_INSTALLED = "anchor_installed";
        public static final String GUARD_INSTALLED = "guard_installed";
        public static final String PRODUCT_NAME = "product_name";
        public static final String SOLVENT_USED = "solvent_used";
        public static final String IGNITION_NEARBY = "ignition_nearby";
        /** 파생 값(묻지 않는다). 설비명에서 정해 판정 기준을 고른다 */
        public static final String EQUIPMENT_KIND = "equipment_kind";

        private SlotKeys() {}

        /** 화면 표시용 질문 문구(현장 말투) */
        public static Map<String, String> questions() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put(WORK_HEIGHT, "작업 높이가 바닥에서 몇 m입니까?");
            m.put(TOP_STEP, "맨 위 발판이나 그 바로 아래 칸에 올라섭니까?");
            m.put(TIP_GUARD, "사다리 넘어짐 방지(아웃트리거, 고정, 잡아주는 사람)가 있습니까?");
            m.put(PLATFORM_GUARDRAIL, "작업대 안전난간이 빠진 곳 없이 설치되어 있습니까?");
            m.put(ANCHOR_INSTALLED, "안전대를 걸 수 있는 부착설비가 있습니까?");
            m.put(GUARD_INSTALLED, "회전, 구동부에 방호덮개가 설치되어 있습니까?");
            m.put(PRODUCT_NAME, "페인트 통 라벨의 제품명을 알려 주세요.");
            m.put(SOLVENT_USED, "유기용제를 취급합니까?");
            m.put(IGNITION_NEARBY, "작업 구역 근처에 용접 같은 화기 작업이 있습니까?");
            return m;
        }

        /** 설비 종류에 맞춘 질문. 사다리면 "사다리 발판 높이", 고소작업대면 "작업대 높이"로 묻는다 */
        public static String question(String key, String kind) {
            if (WORK_HEIGHT.equals(key)) {
                if (KIND_LADDER.equals(kind)) return "사다리 발판 높이가 바닥에서 몇 m입니까?";
                if (KIND_AERIAL_PLATFORM.equals(kind)) return "작업대 높이가 바닥에서 몇 m입니까?";
            }
            return questions().getOrDefault(key, key);
        }

        /** 서식, 결과 카드에 쓰는 값. 높이는 단위를 붙이고, 예/아니요 답은 있음/없음으로 정리한다 */
        public static String displayValue(String key, String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            switch (key) {
                case WORK_HEIGHT -> {
                    Double h = parseHeight(raw);
                    return h == null ? raw.trim() : meters(h).replace("m", " m");
                }
                case TOP_STEP -> {
                    Boolean b = parseTopStep(raw);
                    return b == null ? raw.trim() : (b ? "사용" : "사용 안 함");
                }
                case TIP_GUARD, PLATFORM_GUARDRAIL, ANCHOR_INSTALLED, GUARD_INSTALLED, IGNITION_NEARBY -> {
                    Boolean b = parseBoolean(raw);
                    return b == null ? raw.trim() : (b ? "있음" : "없음");
                }
                default -> {
                    return raw.trim();
                }
            }
        }

        /** 서식, 결과 카드에 쓰는 짧은 항목 이름 */
        public static Map<String, String> labels() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put(WORK_HEIGHT, "발판 높이");
            m.put(TOP_STEP, "최상부 디딤대");
            m.put(TIP_GUARD, "넘어짐 방지");
            m.put(PLATFORM_GUARDRAIL, "작업대 안전난간");
            m.put(ANCHOR_INSTALLED, "안전대 부착설비");
            m.put(GUARD_INSTALLED, "방호덮개");
            m.put(PRODUCT_NAME, "제품명");
            m.put(SOLVENT_USED, "유기용제");
            m.put(IGNITION_NEARBY, "인근 화기 작업");
            return m;
        }
    }
}
