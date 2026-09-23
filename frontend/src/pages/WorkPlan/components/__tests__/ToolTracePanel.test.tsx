import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import ToolTracePanel from "@/pages/WorkPlan/components/ToolTracePanel";

describe("ToolTracePanel", () => {
  it("각 행의 상태를 색 점 외에 숨김 문구로도 전한다", () => {
    render(
      <ToolTracePanel
        connectionState="connected"
        rows={[
          { callOrder: 1, toolName: "findLocationEquipment", params: "{}", status: "ok", durationMs: 12 },
          { callOrder: 2, toolName: "lookupHazards", params: "{}", status: "running" },
          { callOrder: 3, toolName: "saveWorkPlan", params: "{}", status: "failed" },
        ]}
      />,
    );
    expect(screen.getByText("완료")).toHaveClass("sr-only");
    expect(screen.getByText("실행 중")).toHaveClass("sr-only");
    expect(screen.getByText("실패")).toHaveClass("sr-only");
  });
});
