import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import { RiskBadge, StatusBadge } from "@/components/ui/Badge";

describe("Badge", () => {
  it("RiskBadge는 등급 라벨과 risk 토큰 칩을 그린다", () => {
    render(<RiskBadge level="HIGH" />);
    const el = screen.getByText("상");
    expect(el.className).toContain("bg-risk-high-bg");
    expect(el.className).not.toContain("rounded-full");
  });

  it("StatusBadge는 tone 칩을 그린다", () => {
    render(<StatusBadge tone="pending">승인 대기</StatusBadge>);
    expect(screen.getByText("승인 대기").className).toContain("bg-pending-bg");
  });
});
