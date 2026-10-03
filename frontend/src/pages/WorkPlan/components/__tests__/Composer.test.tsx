import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import Composer from "@/pages/WorkPlan/components/Composer";

const PLACEHOLDER = "작업 내용 입력";

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

  it("대화 전에는 새 점검표 머리와 작성 버튼, 예시 칩은 없다", () => {
    render(<Composer disabled={false} onSend={() => {}} showExamples />);
    expect(screen.getByText("새 점검표")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "작성" })).toBeInTheDocument();
    expect(screen.getAllByRole("button")).toHaveLength(1);
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
