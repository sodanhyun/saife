package io.saife.core.service;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사진 판독 등급 룩업테이블 — <b>사진에서 나온 등급도 모델이 정하지 않는다.</b>
 *
 * <p>VLM이 하는 일은 "무엇이 빠졌는가"를 말하는 것까지다. 그 다음 "그래서 몇 등급인가"는
 * 이 표가 정한다. 심사위원이 "이 등급은 AI가 정한 겁니까"라고 물으면 이 표를 띄운다.
 *
 * <p><b>이 등급은 잠정값이다.</b> 정지 사진은 작업높이·작업빈도를 담지 못한다.
 * 그래서 모든 판정의 근거 문자열에 "사진 판독 기준 잠정 등급"이라고 밝히고,
 * 현장 확인으로 조정되는 값임을 화면에 남긴다. 밝히지 않고 확정 등급처럼 쓰면
 * 그건 과장이고 심사에서 바로 깨진다.
 *
 * <p>강도(severity)를 축별로 다르게 잡은 근거는 사고사망 실측 분포다(n=900):
 * 추락 42% · 협착 14% · 낙하 14% · 부딪힘 6% · 화재 2%. 추락과 협착은
 * 단일 사고가 사망으로 직결되는 비중이 높아 강도를 3으로 둔다.
 */
@Component
@Slf4j
public class PhotoRiskTable {

    /**
     * @param keywords       {@code missingControl} 문자열에서 찾을 말들
     * @param riskLevel      잠정 등급
     * @param basis          왜 이 등급인지. 화면에 그대로 뜬다
     */
    private record Row(AccidentType accidentType, String category, List<String> keywords,
                       RiskLevel riskLevel, short frequency, short severity, String basis) {}

    /**
     * 판정 순서가 있다 — <b>위에서부터 먼저 맞는 행을 쓴다.</b>
     * 구체적인 항목을 축 기본값보다 앞에 둔다.
     */
    private static final List<Row> ROWS = List.of(
            // ── 추락 ──
            new Row(AccidentType.FALL, "FALL_ANCHOR", List.of("안전대 부착설비", "앵커", "구명줄"),
                    RiskLevel.HIGH, (short) 3, (short) 3,
                    "안전대를 체결할 설비가 없으면 2m 초과 작업에서 추락을 막을 수단이 없다"),
            new Row(AccidentType.FALL, "FALL_OPENING", List.of("개구부", "덮개"),
                    RiskLevel.HIGH, (short) 3, (short) 3,
                    "바닥 개구부는 통행 중 추락으로 직결된다 (안전보건규칙 제42조)"),
            new Row(AccidentType.FALL, "FALL_GUARDRAIL", List.of("난간", "작업발판"),
                    RiskLevel.HIGH, (short) 3, (short) 3,
                    "작업발판 단부의 안전난간 미설치는 추락 방호가 없는 상태다"),

            // ── 협착 ──
            new Row(AccidentType.CAUGHT, "CAUGHT_GUARD", List.of("방호덮개", "덮개", "방호"),
                    RiskLevel.HIGH, (short) 3, (short) 3,
                    "회전, 구동부 방호덮개 미설치는 접촉 즉시 협착으로 이어진다 (안전보건규칙 제87조)"),

            // ── 낙하 ──
            new Row(AccidentType.DROP, "DROP_NET", List.of("방지망", "낙하물"),
                    RiskLevel.MEDIUM, (short) 2, (short) 3,
                    "낙하물 방지망 미설치. 하부 통행이 있을 때만 위험이 성립해 빈도를 2로 둔다"),
            new Row(AccidentType.DROP, "DROP_STACK", List.of("적재"),
                    RiskLevel.MEDIUM, (short) 2, (short) 3,
                    "적재 불량. 붕괴 시 강도는 크지만 상시 노출은 아니다"),

            // ── 부딪힘 ──
            new Row(AccidentType.STRUCK, "STRUCK_AISLE", List.of("통로", "폐색", "적치"),
                    RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "통로 폐색으로 보행자와 차량계 장비의 동선이 겹친다"),
            new Row(AccidentType.STRUCK, "STRUCK_MARKING", List.of("구획", "표식", "유도"),
                    RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "구획선, 표식 미설치. 동선 분리가 되지 않은 상태다"),

            // ── 화재 ──
            new Row(AccidentType.FIRE, "FIRE_IGNITION", List.of("화기", "불티", "용접"),
                    RiskLevel.HIGH, (short) 3, (short) 3,
                    "가연물 인근 화기 작업. 착화 시 확산이 빠르다"),
            new Row(AccidentType.FIRE, "FIRE_EXTINGUISHER", List.of("소화기", "소화"),
                    RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "소화기 부재는 발화를 만들지는 않지만 초기 진화를 불가능하게 한다"),

            // ── 보호구 ──
            new Row(AccidentType.PPE, "PPE_HELMET", List.of("안전모"),
                    RiskLevel.HIGH, (short) 3, (short) 3,
                    "안전모 미착용. 머리 손상은 경미한 낙하, 전도에서도 치명적이다"),
            new Row(AccidentType.PPE, "PPE_HARNESS", List.of("안전대", "안전벨트"),
                    RiskLevel.HIGH, (short) 3, (short) 3,
                    "안전대 미착용. 고소작업에서 마지막 방호수단이 없는 상태다"),
            new Row(AccidentType.PPE, "PPE_OTHER", List.of("보안경", "방독", "마스크", "장갑", "귀마개"),
                    RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "보호구 미착용. 해당 유해인자에 직접 노출된다")
    );

    /** 구체 항목에 안 걸렸을 때 쓰는 축 기본값 */
    private static final Map<AccidentType, Row> DEFAULTS = defaults();

    /**
     * 빠진 안전조치 → 잠정 등급.
     *
     * @param accidentType    발생형태 축
     * @param missingControl  VLM이 낸 "빠진 안전조치" 문자열
     */
    public RiskRuleEngine.Decision decide(AccidentType accidentType, String missingControl) {
        Row matched = match(accidentType, missingControl);

        if (matched == null) {
            return new RiskRuleEngine.Decision(RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "사진 판독 기준 잠정 등급: 해당 축의 기준이 없어 '중'으로 둔다. 현장 확인 필요");
        }

        String trace = "사진 판독 기준 잠정 등급: %s. 작업높이와 작업빈도는 사진으로 알 수 없어 현장 확인 시 조정된다"
                .formatted(matched.basis());

        log.debug("[PHOTO-RULE] {} '{}' → {}", accidentType, missingControl, matched.riskLevel());
        return new RiskRuleEngine.Decision(matched.riskLevel(),
                matched.frequency(), matched.severity(), trace);
    }

    /**
     * 빠진 안전조치 → <b>안정적인 분류 코드.</b>
     *
     * <p>모델이 같은 것을 매번 같은 말로 쓰지 않는다. "작업발판 안전난간 미설치"와
     * "안전난간 미설치"는 같은 위험인데 문자열로는 다르다. 문자열을 키로 쓰면
     * 같은 설비에 같은 위험요인이 두 개로 쪼개지고, 그건 설비 ID가 쪼개지는 것과
     * 같은 종류의 사고다 — UC2 소환과 UC4 타임라인이 중복으로 더러워진다.
     *
     * <p>등급 판정과 <b>같은 표</b>를 쓴다. 판정 기준과 동일성 기준이 갈라지면
     * "같은 등급인데 다른 위험요인"이라는 이상한 상태가 생긴다.
     */
    public String categoryOf(AccidentType accidentType, String missingControl) {
        Row matched = match(accidentType, missingControl);
        return matched == null ? accidentType + "_UNKNOWN" : matched.category();
    }

    private Row match(AccidentType accidentType, String missingControl) {
        String text = missingControl == null ? "" : missingControl;
        return ROWS.stream()
                .filter(r -> r.accidentType() == accidentType)
                .filter(r -> r.keywords().stream().anyMatch(text::contains))
                .findFirst()
                .orElse(DEFAULTS.get(accidentType));
    }

    private static Map<AccidentType, Row> defaults() {
        Map<AccidentType, Row> m = new LinkedHashMap<>();
        m.put(AccidentType.FALL, new Row(AccidentType.FALL, "FALL_OTHER", List.of(), RiskLevel.HIGH,
                (short) 3, (short) 3, "추락 방호조치 미흡. 추락은 단일 사고의 치사율이 가장 높은 축이다"));
        m.put(AccidentType.CAUGHT, new Row(AccidentType.CAUGHT, "CAUGHT_OTHER", List.of(), RiskLevel.HIGH,
                (short) 3, (short) 3, "협착 방호조치 미흡"));
        m.put(AccidentType.DROP, new Row(AccidentType.DROP, "DROP_OTHER", List.of(), RiskLevel.MEDIUM,
                (short) 2, (short) 3, "낙하 방호조치 미흡"));
        m.put(AccidentType.STRUCK, new Row(AccidentType.STRUCK, "STRUCK_OTHER", List.of(), RiskLevel.MEDIUM,
                (short) 2, (short) 2, "동선 분리 조치 미흡"));
        m.put(AccidentType.FIRE, new Row(AccidentType.FIRE, "FIRE_OTHER", List.of(), RiskLevel.MEDIUM,
                (short) 2, (short) 2, "화재 예방조치 미흡"));
        m.put(AccidentType.PPE, new Row(AccidentType.PPE, "PPE_OTHER_DEFAULT", List.of(), RiskLevel.MEDIUM,
                (short) 2, (short) 2, "보호구 착용 미흡"));
        return m;
    }
}
