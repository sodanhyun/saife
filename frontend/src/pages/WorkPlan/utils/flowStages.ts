// flowStages.ts — 진행 패널의 고정 단계. 백엔드 도구 6종과 1:1이다(화면에는 단계 이름만 보인다).
import type { ToolTraceRow } from "@/types/sse";

export interface FlowStage {
  tool: string;
  title: string;
}

export const FLOW_STAGES: FlowStage[] = [
  { tool: "findLocationEquipment", title: "설비 확인" },
  { tool: "extractWorkPlan", title: "작업 내용 정리" },
  { tool: "analyzeHazards", title: "위험요인 도출" },
  { tool: "searchCases", title: "유사 재해사례" },
  { tool: "getMsds", title: "MSDS 확인" },
  { tool: "createWorkPlan", title: "점검표 작성" },
];

/** 현장 확인 항목의 화면 이름. 백엔드 RiskRuleEngine.SlotKeys.labels()와 같은 말을 쓴다 */
export const SLOT_LABEL: Record<string, string> = {
  work_height: "발판 높이",
  top_step: "최상부 디딤대",
  tip_guard: "넘어짐 방지",
  platform_guardrail: "작업대 안전난간",
  anchor_installed: "안전대 부착설비",
  guard_installed: "방호덮개",
  product_name: "제품명",
};

export type StageState = "idle" | "running" | "ok" | "incomplete" | "failed";

export interface StageView {
  stage: FlowStage;
  state: StageState;
  calls: ToolTraceRow[];
  latest: ToolTraceRow | null;
  /** 마지막 호출이 확인 값 누락으로 멈췄을 때 비어 있던 항목들 */
  missing: string[];
}

/** 대화 전체의 진행 기록을 단계별로 묶는다. 단계 상태는 마지막 호출이 정한다 */
export function toStageViews(rows: ToolTraceRow[]): StageView[] {
  return FLOW_STAGES.map((stage) => {
    const calls = rows.filter((r) => r.toolName === stage.tool);
    const latest = calls.length > 0 ? calls[calls.length - 1] : null;
    const missing = latest?.status === "incomplete" ? latest.missing ?? [] : [];
    return { stage, state: latest ? latest.status : "idle", calls, latest, missing };
  });
}

/** 단계 이름. 진행 중 표시("위험요인 도출 중")에 쓴다 */
export function stageTitle(toolName: string): string {
  return FLOW_STAGES.find((s) => s.tool === toolName)?.title ?? "확인";
}
