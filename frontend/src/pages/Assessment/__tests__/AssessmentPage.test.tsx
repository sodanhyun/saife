import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

vi.mock("@/api/incidentApi", () => ({
  incidentApi: { followUp: vi.fn(), confirmFollowUp: vi.fn() },
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

import { AxiosError, AxiosHeaders } from "axios";

import { incidentApi } from "@/api/incidentApi";
import { followUpFixture } from "@/pages/Assessment/__tests__/fixtures";
import AssessmentPage from "@/pages/Assessment/AssessmentPage";

const mockFollowUp = vi.mocked(incidentApi.followUp);
const mockConfirm = vi.mocked(incidentApi.confirmFollowUp);

function renderAt(path = "/assessment/100") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/assessment/:assessmentId" element={<AssessmentPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mockFollowUp.mockReset();
  mockConfirm.mockReset();
});

describe("AssessmentPage (사고 후 수시평가)", () => {
  it("작성 중: 사고 줄, 근거, 위험요인 카드(등급과 근거), 작업 보류 목록, 확정 버튼", async () => {
    mockFollowUp.mockResolvedValue(followUpFixture());
    renderAt();

    expect(await screen.findByRole("heading", { level: 1, name: "수시평가" })).toBeInTheDocument();
    expect(screen.getByText("작성 중")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "떨어짐 사고, 이동식 사다리 A, 2026-10-02 10:20" })).toHaveAttribute("href", "/incident?incidentId=7");
    expect(screen.getByText("시행규칙 제37조제2항제3호")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "위험성평가표" })).toHaveAttribute("href", "/form/assessment/100");

    const fall = screen.getByRole("article", { name: "작업발판 미확보" });
    expect(within(fall).getByText("떨어짐 사고 발생, 휴업예상 5일, 사고 전 상")).toBeInTheDocument();
    expect(within(fall).getByDisplayValue("이동식 비계(안전난간) 또는 말비계로 작업발판 확보")).toBeInTheDocument();
    expect(within(fall).getByDisplayValue("생산반장 김철수")).toBeInTheDocument();
    // 기존 대책은 기한이 지나 경과일과 함께 보인다
    expect(within(fall).getByText(/기한 2026-09-02, \d+일 경과/)).toBeInTheDocument();

    const hold = screen.getByRole("region", { name: "작업 보류" });
    expect(within(hold).getByText("차양부 천장 도장")).toBeInTheDocument();
    expect(within(hold).getByText("작업 보류")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "확정" })).toBeInTheDocument();
    // 개발 용어, 내부 번호 없음
    expect(screen.queryByText(/#\d|AI|룰/)).toBeNull();
  });

  it("참여 근로자 없이 확정하면 칸에 오류를 보이고 요청하지 않는다", async () => {
    mockFollowUp.mockResolvedValue(followUpFixture());
    renderAt();
    fireEvent.click(await screen.findByRole("button", { name: "확정" }));
    expect(screen.getByText("참여 근로자를 입력하십시오")).toBeInTheDocument();
    expect(mockConfirm).not.toHaveBeenCalled();
  });

  it("참여 근로자를 넣고 확정하면 요청을 보내고, 응답대로 확정일과 재승인 대기를 그린다", async () => {
    mockFollowUp.mockResolvedValue(followUpFixture());
    mockConfirm.mockResolvedValue(
      followUpFixture({
        status: "CONFIRMED",
        confirmedOn: "2026-10-07",
        participants: ["김철수", "이영희"],
        hazards: followUpFixture().hazards.map((h) =>
          h.hazardId === 1
            ? { ...h, action: { actionId: 9, content: "이동식 비계(안전난간) 또는 말비계로 작업발판 확보", owner: "생산반장 김철수", dueDate: "2026-10-21", status: "PENDING", completedOn: null } }
            : h,
        ),
        workPlans: [{ workPlanId: 41, workName: "차양부 천장 도장", workDate: "2026-10-03", status: "SUBMITTED", warning: "" }],
      }),
    );
    renderAt();

    const input = await screen.findByLabelText("참여 근로자 추가");
    fireEvent.change(input, { target: { value: "김철수" } });
    fireEvent.keyDown(input, { key: "Enter" });
    fireEvent.change(screen.getByLabelText("참여 근로자 추가"), { target: { value: "이영희," } });
    expect(screen.getByRole("button", { name: "김철수 빼기" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "확정" }));
    await waitFor(() => expect(mockConfirm).toHaveBeenCalledTimes(1));
    const [id, body] = mockConfirm.mock.calls[0];
    expect(id).toBe(100);
    expect(body.participants).toEqual(["김철수", "이영희"]);
    expect(body.hazards[0]).toMatchObject({ hazardId: 1, acceptable: false, owner: "생산반장 김철수" });

    expect(await screen.findByText("확정 2026-10-07")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "확정" })).toBeNull();
    expect(screen.getByText("재승인 대기")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "보류 해제" })).toBeInTheDocument();
  });

  it("허용 가능으로 바꾸면 대책 칸이 사라진다", async () => {
    mockFollowUp.mockResolvedValue(followUpFixture());
    renderAt();
    const fall = await screen.findByRole("article", { name: "작업발판 미확보" });
    fireEvent.click(within(fall).getByRole("button", { name: "허용 가능" }));
    expect(within(fall).queryByDisplayValue("생산반장 김철수")).toBeNull();
  });

  it("없는 수시평가는 찾을 수 없다고 말하고 사고 보고로 보낸다", async () => {
    mockFollowUp.mockRejectedValue(
      new AxiosError("nf", "404", undefined, undefined, {
        status: 404,
        statusText: "Not Found",
        data: {},
        headers: {},
        config: { headers: new AxiosHeaders() },
      }),
    );
    renderAt("/assessment/999");
    expect(await screen.findByText("수시평가를 찾을 수 없습니다")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "사고 보고" })).toHaveAttribute("href", "/incident");
  });

  it("불러오기 오류는 빈 상태 없이 오류 한 줄과 새로고침", async () => {
    mockFollowUp.mockRejectedValue(new Error("network"));
    renderAt();
    expect(await screen.findByText("불러오지 못했습니다.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "새로고침" })).toBeInTheDocument();
    expect(screen.queryByText("수시평가를 찾을 수 없습니다")).toBeNull();
  });
});
