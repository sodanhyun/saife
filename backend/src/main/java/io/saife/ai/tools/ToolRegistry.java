package io.saife.ai.tools;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 도구 레지스트리 — 에이전트에 전달할 도구 묶음.
 *
 * <p><b>6종을 유지한다.</b> 늘리는 건 자유지만 프로젝터에서 읽히는 줄 수가 상한이다.
 * 시연 트레이스 패널이 큰 글씨 6줄 기준으로 설계되어 있다.
 *
 * <p>순서가 체이닝 힌트가 된다. 장소·설비 조회를 맨 앞에 둔다.
 */
@Component
@RequiredArgsConstructor
@Getter
public class ToolRegistry {

    private final LocationEquipmentTools locationEquipmentTools;  // 1. findLocationEquipment
    private final WorkPlanTools workPlanTools;                    // 2. extractWorkPlan  6. createWorkPlan
    private final HazardAnalysisTools hazardAnalysisTools;        // 3. analyzeHazards  4. searchCases  5. getMsds

    /** ChatClient에 넘길 도구 배열 */
    public Object[] toArray() {
        return new Object[]{
                locationEquipmentTools,
                workPlanTools,
                hazardAnalysisTools
        };
    }
}
