package io.saife.ai.vision;

import io.saife.core.domain.AccidentType;

import java.util.Map;

/**
 * 축별 사진 판독 게이트 통과 여부.
 *
 * <p>2026-09-21 선검증 게이트(30장) 결과다. 근거: {@code docs/vlm-gate-result-20260921.md}.
 * 규칙은 결과를 보기 전에 확정했다({@code docs/vlm-gate-rules-20260921.md}).
 *
 * <p><b>겹침 0인 평가셋 22장으로 다시 시험해 같은 3축이 나왔다.</b>
 * 근거: {@code docs/vlm-eval-result-20260921.md}. 기준을 바꾸지 않고 같은 코드로 돌렸다.
 *
 * <p><b>통과한 축만 사진 판독의 성과로 주장한다.</b> 미통과 축의 후보를 안 받는 것은
 * 아니다 — 게이트에서 미통과 축의 후보 품질 자체는 나쁘지 않았다(반려 0건).
 * 부족했던 건 <b>개수</b>였고, 그건 "그 축의 판정 대상이 정지 사진에 잘 안 잡힌다"는 뜻이다.
 *
 * <p>그래서 미통과 축은 <b>참고용으로 표시하고 채택률 지표에서 분리</b>한다.
 * 기능을 죽이지 않으면서, 검증되지 않은 축의 결과를 검증된 것처럼 세지 않는다.
 * 지표에 넣었다가 심사위원이 게이트 결과를 보면 그때 신뢰를 잃는다.
 */
public enum GateStatus {

    /** 게이트 통과. 사진 판독 결과를 그대로 후보로 올리고 채택률에 센다 */
    PHOTO("사진 판독"),

    /**
     * 게이트 미통과. 후보는 보여주되 <b>참고용</b>으로 표시하고 채택률에서 뺀다.
     * 확정은 현장 확인 후 체크리스트로 한다.
     */
    CHECKLIST("참고, 현장 확인 필요");

    private final String label;

    GateStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 2026-09-21 게이트 결과.
     *
     * <pre>
     * FALL   채택 9 / 반려 0 / 8장  → 통과
     * PPE    채택 8 / 반려 3 / 8장  → 통과
     * DROP   채택 3 / 반려 0 / 3장  → 통과 (마진 없음)
     * STRUCK 채택 2 / 반려 0 / 2장  → 미통과 (데이터 매핑 한계)
     * FIRE   채택 2 / 반려 0 / 2장  → 미통과
     * CAUGHT 채택 1 / 반려 0 / 1장  → 미통과 (정지 사진의 한계)
     * </pre>
     *
     * <p>평가셋 22장이 이 표를 그대로 재현했다 (통과축 구성 동일, 반려율 11% vs 12.5%).
     * 단 {@code DROP}은 <b>두 번 다 정확히 3장</b>이다 — 한 건만 뒤집혀도 미통과다.
     * {@code CAUGHT}와 {@code PPE}는 평가셋이 <b>1장뿐</b>이라 축별 n을 같이 말해야 한다.
     *
     * <p>이 표를 고치려면 게이트를 다시 돌려야 한다. 결과 없이 고치면 그건 주장이다.
     */
    private static final Map<AccidentType, GateStatus> BY_AXIS = Map.of(
            AccidentType.FALL, PHOTO,
            AccidentType.PPE, PHOTO,
            AccidentType.DROP, PHOTO,
            AccidentType.STRUCK, CHECKLIST,
            AccidentType.FIRE, CHECKLIST,
            AccidentType.CAUGHT, CHECKLIST);

    public static GateStatus of(AccidentType axis) {
        return axis == null ? CHECKLIST : BY_AXIS.getOrDefault(axis, CHECKLIST);
    }

    /** 채택률 지표에 셀 수 있는 축인가 */
    public static boolean countsTowardAdoptionRate(AccidentType axis) {
        return of(axis) == PHOTO;
    }
}
