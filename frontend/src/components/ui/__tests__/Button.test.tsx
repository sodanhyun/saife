import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import Button from "@/components/ui/Button";

describe("Button", () => {
  it("loading이면 비활성화되고 라벨이 '처리 중…'으로 바뀐다", () => {
    render(<Button loading>보내기</Button>);
    const btn = screen.getByRole("button");
    expect(btn).toBeDisabled();
    expect(btn).toHaveTextContent("처리 중…");
  });

  it("danger 변형은 risk-high 솔리드다", () => {
    render(<Button variant="danger">사고 등록</Button>);
    expect(screen.getByRole("button").className).toContain("bg-risk-high");
  });
});
