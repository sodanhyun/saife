import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import Input from "@/components/ui/Input";

describe("Input", () => {
  it("error prop이면 risk-high 테두리와 aria-invalid=true가 붙는다", () => {
    render(<Input error placeholder="입력" />);
    const el = screen.getByPlaceholderText("입력");
    expect(el.className).toContain("border-risk-high-border");
    expect(el).toHaveAttribute("aria-invalid", "true");
  });

  it("noWhitespace면 공백을 걸러내고 onChange에 걸러진 값을 전달한다", () => {
    const onChange = vi.fn();
    render(<Input noWhitespace placeholder="식별번호" onChange={onChange} />);
    const el = screen.getByPlaceholderText("식별번호");

    fireEvent.change(el, { target: { value: "a b" } });

    expect(onChange).toHaveBeenCalledTimes(1);
    const event = onChange.mock.calls[0][0];
    expect(event.target.value).toBe("ab");
  });
});
