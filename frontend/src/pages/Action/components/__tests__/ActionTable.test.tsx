import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import { actionRow } from "@/pages/Action/__tests__/fixtures";
import ActionTable from "@/pages/Action/components/ActionTable";
import type { ActionListItem } from "@/types/action";

function renderTable(actions: ActionListItem[], extra: { doneOnly?: boolean; onComplete?: () => void } = {}) {
  return render(
    <MemoryRouter>
      <ActionTable
        actions={actions}
        today="2026-10-03"
        emptyMessage="미이행 개선대책 없음"
        doneOnly={extra.doneOnly}
        onComplete={extra.onComplete ?? vi.fn()}
      />
    </MemoryRouter>,
  );
}

describe("ActionTable", () => {
  it("기한, 경과 일수, 대책과 위험요인 줄, 설비 링크, 담당, 상태", () => {
    renderTable([actionRow({ status: "OVERDUE", dueDate: "2026-09-03", overdueDays: 30 })]);
    expect(screen.getByText("2026-09-03")).toBeInTheDocument();
    expect(screen.getByText("30일 경과")).toBeInTheDocument();
    expect(screen.getByText("차양부 천장 작업 시 이동식 비계(안전난간) 사용")).toBeInTheDocument();
    expect(screen.getByText("떨어짐, 작업발판 미확보")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "이동식 사다리 A" })).toHaveAttribute("href", "/equipment/3");
    expect(screen.getByText("생산반장 김철수")).toBeInTheDocument();
    expect(screen.getByText("기한 경과", { selector: "span" })).toBeInTheDocument();
  });

  it("미이행 행만 이행 확인 버튼이 있고 누르면 그 행을 넘긴다", () => {
    const onComplete = vi.fn();
    const open = actionRow({ id: 1, dueDate: "2026-10-06" });
    const done = actionRow({ id: 2, content: "방호덮개 설치", status: "DONE", completedAt: "2026-09-20T01:00:00Z" });
    renderTable([open, done], { onComplete });

    expect(screen.getByText("D-3")).toBeInTheDocument();
    expect(screen.getByText("미이행")).toBeInTheDocument();
    expect(screen.getByText("완료")).toBeInTheDocument();
    expect(screen.getByText("완료일")).toBeInTheDocument();
    const buttons = screen.getAllByRole("button", { name: /이행 확인/ });
    expect(buttons).toHaveLength(1);
    fireEvent.click(buttons[0]);
    expect(onComplete).toHaveBeenCalledWith(open);
  });

  it("확인된 대책은 증빙 사진과 개선 후 위험성, 확인자를 보인다", () => {
    renderTable([actionRow({ status: "DONE", completedAt: "2026-09-20T01:00:00Z", verifiedBy: "안전관리자 홍길동",
      residualLevel: "LOW", evidenceUrl: "/api/action/1/evidence" })]);
    expect(screen.getByText("개선 후 하, 확인 안전관리자 홍길동")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "증빙 사진" })).toHaveAttribute("src", "/api/action/1/evidence");
  });

  it("완료 탭은 머리글이 완료일이고 둘째 줄 표기가 없다", () => {
    renderTable([actionRow({ status: "DONE", completedAt: "2026-09-20T01:00:00Z" })], { doneOnly: true });
    expect(screen.getByRole("columnheader", { name: "완료일" })).toBeInTheDocument();
    expect(screen.getByText("2026-09-20")).toBeInTheDocument();
    expect(screen.queryByText("완료일", { selector: "div" })).toBeNull();
  });

  it("행이 없으면 빈 상태", () => {
    renderTable([]);
    expect(screen.getByText("미이행 개선대책 없음")).toBeInTheDocument();
  });
});
