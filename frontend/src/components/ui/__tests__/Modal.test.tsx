// src/components/ui/__tests__/Modal.test.tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import Modal from "../Modal";

describe("Modal 키보드 인터랙션", () => {
  it("열릴 때 패널 내 첫 input에 자동 포커스한다", () => {
    render(
      <Modal isOpen onClose={vi.fn()} onConfirm={vi.fn()} title="편집">
        <input aria-label="이름" />
        <input aria-label="설명" />
      </Modal>
    );
    expect(screen.getByLabelText("이름")).toHaveFocus();
  });

  it("ArrowDown으로 다음 input에 포커스 이동한다", () => {
    render(
      <Modal isOpen onClose={vi.fn()} onConfirm={vi.fn()} title="편집">
        <input aria-label="이름" />
        <input aria-label="설명" />
      </Modal>
    );
    const first = screen.getByLabelText("이름");
    expect(first).toHaveFocus();
    fireEvent.keyDown(first, { key: "ArrowDown" });
    expect(screen.getByLabelText("설명")).toHaveFocus();
  });

  it("Enter는 onConfirm을 한 번만 호출한다(훅과 중복되지 않음)", () => {
    const onConfirm = vi.fn();
    render(
      <Modal isOpen onClose={vi.fn()} onConfirm={onConfirm} title="편집">
        <input aria-label="이름" />
      </Modal>
    );
    // 문서 레벨 keydown 핸들러가 Enter 저장을 담당한다.
    fireEvent.keyDown(document, { key: "Enter" });
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it("Enter는 textarea에서 onConfirm을 호출하지 않는다(줄바꿈 유지)", () => {
    const onConfirm = vi.fn();
    render(
      <Modal isOpen onClose={vi.fn()} onConfirm={onConfirm} title="편집">
        <textarea aria-label="메모" />
      </Modal>
    );
    const textarea = screen.getByLabelText("메모");
    fireEvent.keyDown(textarea, { key: "Enter" });
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("ESC는 onClose를 호출한다", () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen onClose={onClose} title="편집">
        <input aria-label="이름" />
      </Modal>
    );
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
