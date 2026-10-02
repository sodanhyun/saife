import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import AgentFlowPanel from "@/pages/WorkPlan/components/AgentFlowPanel";
import type { ToolTraceRow } from "@/types/sse";

const zero = { total: 0, photos: 0, guides: 0, laws: 0, msds: 0 };
const row = (o: Partial<ToolTraceRow>): ToolTraceRow => ({ callOrder: 1, toolName: "findLocationEquipment", params: "{}", status: "ok", startedAt: 0, turn: 1, ...o });

describe("AgentFlowPanel", () => {
  it("제목은 진행이고 여섯 단계를 평문 이름으로 보인다", () => {
    render(<AgentFlowPanel rows={[]} evidence={zero} />);
    expect(screen.getByRole("heading", { name: "진행" })).toBeInTheDocument();
    for (const t of ["설비 확인", "작업 내용 정리", "위험요인 도출", "유사 재해사례", "MSDS 확인", "점검표 작성"]) {
      expect(screen.getByText(t)).toBeInTheDocument();
    }
    expect(screen.queryByText(/findLocationEquipment/)).toBeNull();
  });

  it("완료된 단계에는 결과 한 줄만 붙이고 입력값과 소요 시간은 보이지 않는다", () => {
    render(<AgentFlowPanel evidence={zero}
      rows={[row({ params: JSON.stringify({ query: "공장동 후면 차양부" }), summary: "이동식 사다리 A, 미이행 조치 1건", durationMs: 1200 })]} />);
    expect(screen.getByText("이동식 사다리 A, 미이행 조치 1건")).toBeInTheDocument();
    expect(screen.queryByText("공장동 후면 차양부")).toBeNull();
    expect(screen.queryByText(/1\.2s|ms/)).toBeNull();
  });

  it("확인 값이 비어 멈추면 무엇이 비었는지만 짧게 보인다", () => {
    render(<AgentFlowPanel evidence={zero}
      rows={[row({ toolName: "extractWorkPlan", status: "incomplete", missing: ["work_height", "top_step"] })]} />);
    expect(screen.getByText("확인 필요: 발판 높이, 최상부 디딤대")).toBeInTheDocument();
  });

  it("다음 호출이 채워지면 확인 필요 줄이 사라진다", () => {
    render(<AgentFlowPanel evidence={zero} rows={[
      row({ toolName: "extractWorkPlan", status: "incomplete", missing: ["work_height"] }),
      row({ toolName: "extractWorkPlan", callOrder: 1, turn: 2, status: "ok", summary: "천장 페인트 작업, 10-03, 작업자 2명" }),
    ]} />);
    expect(screen.queryByText(/확인 필요/)).toBeNull();
    expect(screen.getByText("천장 페인트 작업, 10-03, 작업자 2명")).toBeInTheDocument();
  });

  it("근거가 모이면 종류별 개수를 바닥에 보인다", () => {
    render(<AgentFlowPanel rows={[]} evidence={{ total: 9, photos: 1, guides: 2, laws: 4, msds: 1 }} />);
    expect(screen.getByText("재해사례").nextSibling).toHaveTextContent("2");
    expect(screen.getByText("조문").nextSibling).toHaveTextContent("4");
  });
});
