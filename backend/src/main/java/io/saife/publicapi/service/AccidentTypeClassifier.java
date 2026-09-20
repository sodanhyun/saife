package io.saife.publicapi.service;

import io.saife.core.domain.AccidentType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 공공 사례를 6축으로 분류한다.
 *
 * <p><b>모델을 쓰지 않는다.</b> 6,372건 + 2,940건을 분류하는 데 모델을 쓰면
 * 수집이 몇 시간짜리 작업이 되고 쿼터를 먹는다. 그리고 이 데이터는 원문이
 * 사고 동작을 거의 그대로 쓴다 — "추락", "끼임", "부딪힘"이 문장에 박혀 있다.
 * 키워드 매칭으로 충분하고, 무엇보다 <b>결정론적이라 재실행해도 같은 결과가 나온다.</b>
 *
 * <p>분류에 실패하면 null을 돌려 둔다. 억지로 한 축에 넣으면 사례 검색이
 * 엉뚱한 근거를 올린다 — "근거로서 약하다"보다 "근거가 틀렸다"가 훨씬 나쁘다.
 */
@Component
public class AccidentTypeClassifier {

    private record Rule(AccidentType type, List<String> keywords) {}

    /**
     * 순서가 있다. 위에서부터 먼저 맞는 축을 쓴다.
     *
     * <p>추락을 맨 위에 두는 이유는 실측 분포에서 42%로 가장 많고,
     * "지붕에서 떨어져 부딪힘"처럼 두 축이 한 문장에 있을 때 1차 원인이 추락이기 때문이다.
     */
    private static final List<Rule> RULES = List.of(
            new Rule(AccidentType.FALL, List.of(
                    "추락", "떨어", "실족", "전락", "개구부", "사다리에서", "비계에서", "지붕")),
            new Rule(AccidentType.CAUGHT, List.of(
                    "끼임", "끼여", "협착", "말려", "감김", "감겨", "롤러에", "회전부")),
            new Rule(AccidentType.DROP, List.of(
                    "낙하", "떨어진", "붕괴", "무너", "깔림", "깔려", "전도된", "적재물")),
            new Rule(AccidentType.STRUCK, List.of(
                    "부딪힘", "부딪혀", "충돌", "치임", "치여", "들이받", "역과")),
            new Rule(AccidentType.FIRE, List.of(
                    "화재", "폭발", "착화", "화상", "불티", "유증기", "인화")),
            new Rule(AccidentType.PPE, List.of(
                    "보호구", "안전모 미착용", "안전대 미착용", "미착용")));

    /**
     * @param keyword  정규화된 한 줄 요약. <b>여기를 먼저 본다</b> — 사고 동작이 압축돼 있다
     * @param contents 본문. keyword로 못 정하면 본다
     * @return 6축 중 하나, 또는 분류 불가 시 null
     */
    public AccidentType classify(String keyword, String contents) {
        AccidentType fromKeyword = match(keyword);
        if (fromKeyword != null) {
            return fromKeyword;
        }
        return match(contents);
    }

    private AccidentType match(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Rule rule : RULES) {
            for (String kw : rule.keywords()) {
                if (text.contains(kw)) {
                    return rule.type();
                }
            }
        }
        return null;
    }
}
