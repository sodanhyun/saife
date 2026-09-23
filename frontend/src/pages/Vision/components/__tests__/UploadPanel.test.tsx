import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import UploadPanel from "@/pages/Vision/components/UploadPanel";

const base = { equipment: [], equipmentId: null, onEquipmentChange: () => {}, preview: null, onPick: () => {} };

describe("UploadPanel", () => {
  it("판독 중에는 버튼을 막되 진행 문구를 그대로 보여준다(처리 중…으로 덮지 않는다)", () => {
    render(<UploadPanel {...base} analyzing progress="후보 판정 중" />);
    const btn = screen.getByRole("button", { name: "후보 판정 중" });
    expect(btn).toBeDisabled();
    expect(screen.queryByText("처리 중…")).toBeNull();
  });

  it("파일 입력과 설비 선택에 접근 가능한 이름이 있다", () => {
    render(<UploadPanel {...base} analyzing={false} progress={null} />);
    expect(screen.getByLabelText("사진 파일")).toHaveAttribute("type", "file");
    expect(screen.getByLabelText("대상 설비").tagName).toBe("SELECT");
    expect(screen.getByRole("button", { name: "사진 선택" })).toBeEnabled();
  });
});
