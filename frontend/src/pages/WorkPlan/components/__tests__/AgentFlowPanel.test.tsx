import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import AgentFlowPanel from "@/pages/WorkPlan/components/AgentFlowPanel";
import type { ToolTraceRow } from "@/types/sse";

const zero = { total: 0, photos: 0, guides: 0, laws: 0, msds: 0 };
const row = (o: Partial<ToolTraceRow>): ToolTraceRow => ({ callOrder: 1, toolName: "findLocationEquipment", params: "{}", status: "ok", startedAt: 0, turn: 1, ...o });

describe("AgentFlowPanel", () => {
  it("호출 전에는 6단계를 목적 문구와 함께 대기로 보인다", () => {
    render(<AgentFlowPanel rows={[]} connectionState="idle" streaming={false} evidence={zero} />);
    expect(screen.getByText("도구 6종 대기")).toBeInTheDocument();
    expect(screen.getAllByText("대기")).toHaveLength(6);
  });

  it("도구 결과 요약과 입력값을 단계에 붙인다", () => {
    render(<AgentFlowPanel connectionState="idle" streaming={false} evidence={zero}
      rows={[row({ params: JSON.stringify({ query: "공장동 후면 차양부", locationTag: "null" }), summary: "이동식 사다리 A 확정" })]} />);
    expect(screen.getByText("공장동 후면 차양부")).toBeInTheDocument();
    expect(screen.queryByText("null")).toBeNull();
    expect(screen.getByText("이동식 사다리 A 확정")).toBeInTheDocument();
  });

  it("필수 값이 비어 되묻기로 갈라지면 판단 문장을 보인다", () => {
    render(<AgentFlowPanel connectionState="idle" streaming={false} evidence={zero}
      rows={[row({ toolName: "extractWorkPlan", status: "incomplete", missing: ["work_height", "product_name"] })]} />);
    expect(screen.getByText("판단: 필수 값 비어 있음, 작업 높이, 사용 제품명 되묻기")).toBeInTheDocument();
  });

  it("근거 종류별 개수를 바닥에 보인다", () => {
    render(<AgentFlowPanel rows={[]} connectionState="idle" streaming={false} evidence={{ total: 9, photos: 1, guides: 2, laws: 4, msds: 1 }} />);
    expect(screen.getByText("사고사례").previousSibling).toHaveTextContent("2");
    expect(screen.getByText("법 조문").previousSibling).toHaveTextContent("4");
  });
});
