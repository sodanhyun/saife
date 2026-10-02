import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import IncidentTable from "@/pages/Incident/components/IncidentTable";
import type { IncidentListItem } from "@/types/incident";

const base: IncidentListItem = {
  id: 1,
  equipmentId: 1,
  equipmentName: "이동식 사다리 A",
  occurredAt: "2026-10-02T10:20:00+09:00",
  incidentType: "FALL",
  incidentTypeLabel: "떨어짐",
  severity: "LOST_TIME",
  leaveDays: 5,
  reportStatus: "REQUIRED",
  reportStatusLabel: "제출 필요",
  reportDueDate: "2026-11-02",
  daysRemaining: 31,
  reportSubmittedOn: null,
  followUpAssessmentId: 9,
};

describe("IncidentTable", () => {
  it("기한은 날짜로 보이고 D-n 숫자를 쓰지 않는다. 내부 번호도 보이지 않는다", () => {
    render(<IncidentTable incidents={[base]} />);
    expect(screen.getByText("2026-11-02")).toBeInTheDocument();
    expect(screen.getByText("떨어짐")).toBeInTheDocument();
    expect(screen.queryByText(/D-/)).toBeNull();
    expect(screen.queryByText("#9")).toBeNull();
  });

  it("제출 완료면 기한 대신 제출일을, 기한이 지났으면 n일 경과", () => {
    render(
      <IncidentTable
        incidents={[
          { ...base, id: 1, reportStatus: "SUBMITTED", reportStatusLabel: "제출 완료", reportSubmittedOn: "2026-10-12" },
          { ...base, id: 2, reportStatus: "OVERDUE", reportStatusLabel: "기한 경과", reportDueDate: "2026-09-15", daysRemaining: -17 },
        ]}
      />,
    );
    expect(screen.queryByText("2026-11-02")).toBeNull();
    expect(screen.getByText("제출 2026-10-12")).toBeInTheDocument();
    expect(screen.getByText("17일 경과")).toBeInTheDocument();
  });

  it("아차사고는 휴업 칸에 아차사고로 쓴다", () => {
    render(<IncidentTable incidents={[{ ...base, severity: "NEAR_MISS", leaveDays: null, reportStatus: "NOT_REQUIRED", reportStatusLabel: "제출 의무 없음", reportDueDate: null }]} />);
    expect(screen.getByText("아차사고")).toBeInTheDocument();
  });

  it("행을 누르면 그 사고를 열고, 불러오는 동안 그 행에 진행 표시가 뜬다", () => {
    const onOpen = vi.fn();
    const { rerender } = render(<IncidentTable incidents={[base]} onOpen={onOpen} />);
    fireEvent.click(screen.getByText("이동식 사다리 A"));
    expect(onOpen).toHaveBeenCalledWith(1);
    expect(screen.queryByLabelText("불러오는 중")).toBeNull();
    rerender(<IncidentTable incidents={[base]} onOpen={onOpen} openingId={1} />);
    expect(screen.getByLabelText("불러오는 중")).toBeInTheDocument();
  });
});
