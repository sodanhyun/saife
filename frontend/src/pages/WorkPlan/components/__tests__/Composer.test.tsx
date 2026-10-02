import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import Composer from "@/pages/WorkPlan/components/Composer";
import { EXAMPLES } from "@/pages/WorkPlan/utils/examples";

const PLACEHOLDER = "예: 내일 차양부 천장 도장, 사다리 사용, 김철수 반장 외 1명";

describe("Composer", () => {
  it("한글 IME 조합 중 Enter는 전송하지 않는다", () => {
    const onSend = vi.fn();
    render(<Composer disabled={false} onSend={onSend} />);
    const textarea = screen.getByPlaceholderText(PLACEHOLDER);
    fireEvent.change(textarea, { target: { value: "내일 사다리 작업" } });
    fireEvent.keyDown(textarea, { key: "Enter", isComposing: true });
    expect(onSend).not.toHaveBeenCalled();
  });

  it("조합이 끝난 평범한 Enter는 전송한다", () => {
    const onSend = vi.fn();
    render(<Composer disabled={false} onSend={onSend} />);
    const textarea = screen.getByPlaceholderText(PLACEHOLDER);
    fireEvent.change(textarea, { target: { value: "내일 사다리 작업" } });
    fireEvent.keyDown(textarea, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith("내일 사다리 작업", undefined);
  });

  it("입력창에 접근 가능한 이름(작업 내용)이 있다", () => {
    render(<Composer disabled={false} onSend={() => {}} />);
    expect(screen.getByLabelText("작업 내용").tagName).toBe("TEXTAREA");
  });

  it("빈 화면의 예시 칩을 누르면 입력창에 채운다", () => {
    render(<Composer disabled={false} onSend={() => {}} showExamples />);
    fireEvent.click(screen.getByRole("button", { name: EXAMPLES[0] }));
    expect(screen.getByLabelText("작업 내용")).toHaveValue(EXAMPLES[0]);
  });

  it("확인이 필요한 항목이 있으면 짧게 표시하고 답을 그 항목으로 보낸다", () => {
    const onSend = vi.fn();
    render(<Composer disabled={false} onSend={onSend} slot={{ slotKey: "work_height", question: "사다리 발판 높이가 바닥에서 몇 m입니까?" }} />);
    expect(screen.getByText("확인 필요: 발판 높이")).toBeInTheDocument();
    const textarea = screen.getByPlaceholderText("답변 입력");
    fireEvent.change(textarea, { target: { value: "3.2m요" } });
    fireEvent.keyDown(textarea, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith("3.2m요", "work_height");
  });
});
