import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import Composer from "@/pages/WorkPlan/components/Composer";

describe("Composer", () => {
  it("한글 IME 조합 중 Enter는 전송하지 않는다", () => {
    const onSend = vi.fn();
    render(<Composer disabled={false} onSend={onSend} />);
    const textarea = screen.getByPlaceholderText("작업 내용을 입력하세요");
    fireEvent.change(textarea, { target: { value: "내일 사다리 작업" } });
    fireEvent.keyDown(textarea, { key: "Enter", isComposing: true });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("조합이 끝난 평범한 Enter는 전송한다", () => {
    const onSend = vi.fn();
    render(<Composer disabled={false} onSend={onSend} />);
    const textarea = screen.getByPlaceholderText("작업 내용을 입력하세요");
    fireEvent.change(textarea, { target: { value: "내일 사다리 작업" } });
    fireEvent.keyDown(textarea, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith("내일 사다리 작업");
  });
});
