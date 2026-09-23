// src/components/ui/__tests__/DataTable.test.tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import DataTable, { type Column } from "../DataTable";

interface Row {
  id: number;
  name: string;
}

const rows: Row[] = [
  { id: 1, name: "첫줄" },
  { id: 2, name: "둘줄" },
];

const baseColumns: Column<Row>[] = [{ key: "name", header: "이름", render: (r) => r.name }];

describe("DataTable 키보드 접근성 (onRowClick 행)", () => {
  it("onRowClick 지정 시 행에 tabIndex=0과 role=button을 부여한다", () => {
    render(<DataTable columns={baseColumns} data={rows} rowKey={(r) => r.id} onRowClick={vi.fn()} />);
    const row = screen.getByText("첫줄").closest("tr")!;
    expect(row).toHaveAttribute("tabindex", "0");
    expect(row).toHaveAttribute("role", "button");
  });

  it("onRowClick 미지정 시 행에 tabIndex·role을 부여하지 않는다(정적 행 오염 금지)", () => {
    render(<DataTable columns={baseColumns} data={rows} rowKey={(r) => r.id} />);
    const row = screen.getByText("첫줄").closest("tr")!;
    expect(row).not.toHaveAttribute("tabindex");
    expect(row).not.toHaveAttribute("role");
  });

  it("Enter keydown 시 해당 행 데이터로 onRowClick이 호출된다", () => {
    const onRowClick = vi.fn();
    render(<DataTable columns={baseColumns} data={rows} rowKey={(r) => r.id} onRowClick={onRowClick} />);
    const row = screen.getByText("둘줄").closest("tr")!;
    fireEvent.keyDown(row, { key: "Enter" });
    expect(onRowClick).toHaveBeenCalledWith(rows[1]);
  });

  it("Space keydown 시에도 onRowClick이 호출되고 기본 스크롤 동작은 막힌다", () => {
    const onRowClick = vi.fn();
    render(<DataTable columns={baseColumns} data={rows} rowKey={(r) => r.id} onRowClick={onRowClick} />);
    const row = screen.getByText("첫줄").closest("tr")!;
    const notDefaultPrevented = fireEvent.keyDown(row, { key: " " });
    expect(onRowClick).toHaveBeenCalledWith(rows[0]);
    expect(notDefaultPrevented).toBe(false);
  });

  it("다른 키(예: Tab)는 onRowClick을 호출하지 않는다", () => {
    const onRowClick = vi.fn();
    render(<DataTable columns={baseColumns} data={rows} rowKey={(r) => r.id} onRowClick={onRowClick} />);
    const row = screen.getByText("첫줄").closest("tr")!;
    fireEvent.keyDown(row, { key: "Tab" });
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it("행 내부 인터랙티브 요소(버튼)에서 발생한 Enter는 onRowClick을 중복 호출하지 않는다", () => {
    const onRowClick = vi.fn();
    const columnsWithButton: Column<Row>[] = [
      ...baseColumns,
      { key: "action", header: "액션", render: () => <button type="button">편집</button> },
    ];
    render(<DataTable columns={columnsWithButton} data={rows} rowKey={(r) => r.id} onRowClick={onRowClick} />);
    const [button] = screen.getAllByRole("button", { name: "편집" });
    fireEvent.keyDown(button, { key: "Enter" });
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it("onRowClick 지정 시 focus-visible 링 클래스를 포함한다", () => {
    render(<DataTable columns={baseColumns} data={rows} rowKey={(r) => r.id} onRowClick={vi.fn()} />);
    const row = screen.getByText("첫줄").closest("tr")!;
    expect(row.className).toMatch(/focus-visible:ring/);
  });

  it("onRowClick 미지정 시 클릭 동작·hover 스타일은 유지되지만 focus-visible 링 클래스는 없다", () => {
    render(<DataTable columns={baseColumns} data={rows} rowKey={(r) => r.id} />);
    const row = screen.getByText("첫줄").closest("tr")!;
    expect(row.className).toMatch(/hover:bg-slate-50/);
    expect(row.className).not.toMatch(/focus-visible:ring/);
  });
});
