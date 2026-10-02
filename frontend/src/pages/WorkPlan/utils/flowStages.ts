// flowStages.ts — 에이전트 작업 흐름 패널의 고정 단계. 도구 6종과 1:1이다.
// 목적 문구는 각 도구 설명(@Tool purpose)을 화면 말로 줄인 것이다. 모델의 속마음을 지어내지 않는다.
import type { ToolTraceRow } from "@/types/sse";

export interface FlowStage {
  tool: string;
  title: string;
  purpose: string;
}

export const FLOW_STAGES: FlowStage[] = [
  { tool: "findLocationEquipment", title: "장소와 설비 확인", purpose: "말한 장소를 설비 대장에 붙이고, 그 설비의 이력을 불러온다" },
  { tool: "extractWorkPlan", title: "작업계획서 구조화", purpose: "발화를 서식 항목으로 채우고, 비어 있는 필수 값만 골라낸다" },
  { tool: "analyzeHazards", title: "위험요인 도출", purpose: "작업, 설비, 물질 조합으로 발생형태를 고르고 지침과 조문을 붙인다" },
  { tool: "searchCases", title: "유사 사고사례 검색", purpose: "공단 사고사례 9,318건에서 같은 발생형태의 실제 사례를 찾는다" },
  { tool: "getMsds", title: "MSDS 조회", purpose: "사용 제품의 유해성, 화재 대처, 보호구를 확인한다" },
  { tool: "createWorkPlan", title: "제출과 작업 전 브리핑", purpose: "룰 엔진으로 등급을 확정하고 브리핑을 만들어 승인 대기에 올린다" },
];

/** 되묻기 슬롯 키의 화면 이름 */
export const SLOT_LABEL: Record<string, string> = {
  work_height: "작업 높이",
  anchor_installed: "안전대 부착설비",
  product_name: "사용 제품명",
  guard_installed: "방호덮개",
};

export type StageState = "idle" | "running" | "ok" | "incomplete" | "failed";

export interface StageView {
  stage: FlowStage;
  state: StageState;
  calls: ToolTraceRow[];
  latest: ToolTraceRow | null;
  /** 한 번이라도 필수 값 누락으로 되묻기 분기를 탔으면 그때 비어 있던 항목들 */
  askedFor: string[];
}

/** 대화 전체의 트레이스를 단계별로 묶는다. 단계 상태는 마지막 호출이 정한다 */
export function toStageViews(rows: ToolTraceRow[]): StageView[] {
  return FLOW_STAGES.map((stage) => {
    const calls = rows.filter((r) => r.toolName === stage.tool);
    const latest = calls.length > 0 ? calls[calls.length - 1] : null;
    const asked = new Set<string>();
    calls.forEach((c) => c.missing?.forEach((m) => asked.add(m)));
    return { stage, state: latest ? latest.status : "idle", calls, latest, askedFor: [...asked] };
  });
}

/** 파라미터 JSON에서 화면에 보일 값만. "null"·빈 값은 뺀다 */
export function paramValues(params: string): string[] {
  try {
    const parsed = JSON.parse(params) as Record<string, unknown>;
    return Object.values(parsed)
      .map((v) => (v === null || v === undefined ? "" : String(v)))
      .filter((v) => v !== "" && v !== "null" && v !== "undefined");
  } catch {
    return [];
  }
}

/** 되묻는 이유. 백엔드 IncompleteResult.why와 같은 말을 짧게 쓴다 */
export const SLOT_REASON: Record<string, string> = {
  work_height: "2m를 넘으면 추락 등급과 안전대 요구가 달라집니다",
  anchor_installed: "부착설비가 없으면 2m 초과 작업은 '상'으로 올라갑니다",
  product_name: "유기용제 여부로 화재 등급과 보호구가 달라집니다",
  guard_installed: "덮개가 없으면 협착 등급이 '상'으로 올라갑니다",
};
