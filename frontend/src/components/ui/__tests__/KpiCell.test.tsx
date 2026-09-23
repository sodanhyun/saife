// src/components/ui/__tests__/KpiCell.test.tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import KpiCell from "@/components/ui/KpiCell";

describe("KpiCell (대시보드 KPI 6셀 공용 — D36·D40)", () => {
  it("라벨과 값을 렌더링한다", () => {
    render(<KpiCell label="오늘 등록" value={12} />);
    expect(screen.getByText("오늘 등록")).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
  });

  it("subText가 주어지면 함께 렌더링한다", () => {
    render(<KpiCell label="미결 총계" value={17} subText="클릭 → 작업 큐" />);
    expect(screen.getByText("클릭 → 작업 큐")).toBeInTheDocument();
  });

  it("onClick 미제공 시 정적 셀(div)로 렌더되고 button role이 없다", () => {
    render(<KpiCell label="오늘 등록" value={12} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("onClick 제공 시 button 시맨틱으로 렌더된다(D40)", () => {
    const onClick = vi.fn();
    render(<KpiCell label="미결 총계" value={17} onClick={onClick} />);
    expect(screen.getByRole("button")).toBeInTheDocument();
  });

  it("클릭 시 onClick이 호출된다", () => {
    const onClick = vi.fn();
    render(<KpiCell label="미결 총계" value={17} onClick={onClick} />);
    fireEvent.click(screen.getByRole("button"));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("클릭 셀은 네이티브 <button> 태그다(Enter 키 활성화는 브라우저 기본 동작 — D40)", () => {
    render(<KpiCell label="미결 총계" value={17} onClick={() => {}} />);
    expect(screen.getByRole("button").tagName).toBe("BUTTON");
  });

  it("클릭 셀은 포커스 링 클래스를 포함한다", () => {
    render(<KpiCell label="미결 총계" value={17} onClick={() => {}} />);
    expect(screen.getByRole("button").className).toMatch(/focus:ring/);
  });

  it("tone=high면 risk-high 연톤 글자색을 적용한다", () => {
    render(<KpiCell label="기한 경과" value={3} tone="high" subText="즉시 조치" />);
    expect(screen.getByText("3").className).toMatch(/text-risk-high-text/);
  });

  it("tone=pending이면 pending 연톤을 적용한다", () => {
    render(<KpiCell label="제출 기한" value="D-30" tone="pending" />);
    expect(screen.getByText("D-30").className).toMatch(/text-pending-text/);
  });

  it("tone 없으면 상태색 클래스가 붙지 않는다", () => {
    render(<KpiCell label="위험성평가" value={12} />);
    expect(screen.getByText("12").className).not.toMatch(/risk-|pending-/);
  });

  it("title(정의 툴팁)을 네이티브 title 속성으로 전달한다", () => {
    render(<KpiCell label="자동 NG율" value="23.8%" title="기간 내 자동판정 NG 비율" />);
    expect(screen.getByTitle("기간 내 자동판정 NG 비율")).toBeInTheDocument();
  });
});
