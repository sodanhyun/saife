import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import Callout from "@/components/ui/Callout";

describe("Callout", () => {
  it("tone 연톤 배경·테두리를 입히고 제목을 굵게 그린다", () => {
    render(<Callout tone="high" title="이 사고는 예고되어 있었습니다">본문</Callout>);
    const title = screen.getByText("이 사고는 예고되어 있었습니다");
    expect(title.className).toContain("font-semibold");
    expect(title.closest("[role=note]")!.className).toContain("bg-risk-high-bg");
  });
});
