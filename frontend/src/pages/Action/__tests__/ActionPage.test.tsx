import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/actionApi", () => ({
  actionApi: { search: vi.fn(), counts: vi.fn(), complete: vi.fn() },
}));
vi.mock("@/stores/useToastStore", () => {
  const state = { error: vi.fn(), success: vi.fn(), info: vi.fn(), warning: vi.fn() };
  type State = typeof state;
  const useToastStore = Object.assign(
    (selector?: (s: State) => unknown) => (selector ? selector(state) : state),
    { getState: () => state },
  );
  return { useToastStore };
});

import { actionApi } from "@/api/actionApi";
import { actionRow } from "@/pages/Action/__tests__/fixtures";
import ActionPage from "@/pages/Action/ActionPage";
import { useToastStore } from "@/stores/useToastStore";
import type { ActionListItem } from "@/types/action";
import type { PaginationResponse } from "@/types/common";

const mockSearch = vi.mocked(actionApi.search);
const mockCounts = vi.mocked(actionApi.counts);
const mockComplete = vi.mocked(actionApi.complete);

function page(content: ActionListItem[], total = content.length): PaginationResponse<ActionListItem> {
  return { content, number: 0, size: 20, totalPages: 1, totalElements: total };
}

function LocationProbe() {
  const loc = useLocation();
  return <div data-testid="location">{loc.search}</div>;
}

function renderAt(path = "/action") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/action"
          element={
            <>
              <ActionPage />
              <LocationProbe />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mockSearch.mockReset();
  mockCounts.mockReset();
  mockComplete.mockReset();
  mockCounts.mockResolvedValue({ open: 9, overdue: 3, done: 45 });
});

describe("ActionPage (개선대책)", () => {
  it("제목만 있고 설명 문구가 없다. 탭 건수와 목록", async () => {
    mockSearch.mockResolvedValue(page([actionRow()]));
    renderAt();

    expect(await screen.findByRole("heading", { level: 1, name: "개선대책" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "미이행 9" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "기한 경과 3" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "완료 45" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "전체" })).toBeInTheDocument();
    expect(screen.getByPlaceholderText("내용, 설비, 담당")).toBeInTheDocument();
    expect(mockSearch).toHaveBeenCalledWith(expect.objectContaining({ status: "OPEN", page: 0, size: 20 }), expect.anything());
    const header = screen.getByRole("heading", { level: 1 }).parentElement!;
    expect(within(header).queryByText(/./, { selector: "p" })).toBeNull();
  });

  it("?status=OVERDUE로 들어오면 기한 경과 탭이 선택된다. 탭을 바꾸면 URL도 바뀐다", async () => {
    mockSearch.mockResolvedValue(page([]));
    renderAt("/action?status=OVERDUE");

    expect(await screen.findByRole("button", { name: "기한 경과 3" })).toHaveAttribute("aria-pressed", "true");
    expect(mockSearch).toHaveBeenCalledWith(expect.objectContaining({ status: "OVERDUE" }), expect.anything());
    expect(await screen.findByText("기한 경과 개선대책 없음")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "완료 45" }));
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("?status=DONE"));
    await waitFor(() =>
      expect(mockSearch).toHaveBeenLastCalledWith(expect.objectContaining({ status: "DONE" }), expect.anything()),
    );
  });

  it("검색어는 디바운스 뒤 서버로 간다", async () => {
    mockSearch.mockResolvedValue(page([]));
    renderAt();
    await screen.findByRole("heading", { level: 1, name: "개선대책" });

    fireEvent.change(screen.getByPlaceholderText("내용, 설비, 담당"), { target: { value: "사다리" } });
    await waitFor(() =>
      expect(mockSearch).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: "사다리" }), expect.anything()),
    );
    expect(await screen.findByText("검색 결과 없음")).toBeInTheDocument();
  });

  it("이행 완료: 확인 창에 대책 내용, 확정하면 완료 기록 후 목록과 건수를 다시 받는다", async () => {
    const row = actionRow({ id: 7 });
    mockSearch.mockResolvedValue(page([row]));
    mockComplete.mockResolvedValue({
      id: 7, hazardId: 1, assessmentId: 1, equipmentId: 3, content: row.content, owner: row.owner, dueDate: row.dueDate,
      status: "DONE", guideRef: null, completedAt: "2026-10-03T03:00:00Z", createdAt: "2026-09-01T00:00:00Z", priority: null,
    });
    renderAt();

    fireEvent.click(await screen.findByRole("button", { name: `${row.content} 이행 완료` }));
    const dialog = await screen.findByRole("dialog", { name: "이행 완료" });
    expect(within(dialog).getByText(row.content)).toBeInTheDocument();
    const searchCalls = mockSearch.mock.calls.length;
    const countCalls = mockCounts.mock.calls.length;

    fireEvent.click(within(dialog).getByRole("button", { name: "이행 완료" }));
    await waitFor(() => expect(mockComplete).toHaveBeenCalledWith(7));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(useToastStore.getState().success).toHaveBeenCalledWith("이행 완료로 기록함");
    await waitFor(() => expect(mockSearch.mock.calls.length).toBeGreaterThan(searchCalls));
    expect(mockCounts.mock.calls.length).toBeGreaterThan(countCalls);
  });

  it("취소하면 기록하지 않는다", async () => {
    mockSearch.mockResolvedValue(page([actionRow()]));
    renderAt();
    fireEvent.click(await screen.findByRole("button", { name: /이행 완료$/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "취소" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(mockComplete).not.toHaveBeenCalled();
  });

  it("더 보기는 남은 건수가 있을 때만, 누르면 크기를 늘려 다시 받는다", async () => {
    mockSearch.mockResolvedValue(page([actionRow()], 25));
    renderAt();
    fireEvent.click(await screen.findByRole("button", { name: "더 보기 (1/25)" }));
    await waitFor(() =>
      expect(mockSearch).toHaveBeenLastCalledWith(expect.objectContaining({ size: 40 }), expect.anything()),
    );
  });

  it("조회 실패면 오류 안내", async () => {
    mockSearch.mockRejectedValue(new Error("x"));
    renderAt();
    expect(await screen.findByRole("alert")).toHaveTextContent("불러오지 못했습니다.");
  });
});
