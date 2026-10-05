import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import PhotoPanel from "@/pages/Vision/components/PhotoPanel";

describe("PhotoPanel", () => {
  const marks = [{ no: 1, box: [150, 430, 980, 640], level: "HIGH" as const, label: "최상부 디딤대 사용" }];

  it("분석이 끝나면 위험요인 위치를 번호 상자로 사진 위에 표시한다", () => {
    render(<PhotoPanel preview="blob:x" analyzing={false} stage={null} onPick={vi.fn()} marks={marks} />);
    const img = screen.getByAltText("순회점검 현장 사진");
    Object.defineProperty(img, "naturalWidth", { value: 1600 });
    Object.defineProperty(img, "naturalHeight", { value: 1200 });
    fireEvent.load(img);
    const box = screen.getByLabelText("위치 1 최상부 디딤대 사용");
    expect(box).toHaveStyle({ top: "15%", left: "43%", height: "83%", width: "21%" });
    expect(box).toHaveTextContent("1최상부 디딤대 사용");
  });

  it("분석 중에는 상자를 그리지 않는다", () => {
    render(<PhotoPanel preview="blob:x" analyzing stage={null} onPick={vi.fn()} marks={marks} />);
    fireEvent.load(screen.getByAltText("순회점검 현장 사진"));
    expect(screen.queryByLabelText(/위치 1/)).toBeNull();
  });
});
