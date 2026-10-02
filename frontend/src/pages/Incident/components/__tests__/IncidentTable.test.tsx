import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import IncidentTable from "@/pages/Incident/components/IncidentTable";
import type { IncidentListItem } from "@/types/incident";

const base: IncidentListItem = {
  id: 1,
  equipmentId: 1,
  equipmentName: "이동식 사다리 A",
  occurredAt: "2026-10-02T10:20:00+09:00",
  accidentType: "FALL",
  severity: "LOST_TIME",
  leaveDays: 5,
  reportStatus: "REQUIRED",
  reportStatusLabel: "제출 필요",
  reportDueDate: "2026-11-02",
  daysRemaining: 31,
  followUpAssessmentId: 9,
};

describe("IncidentTable", () => {
  it("기한은 날짜로 보이고 D-n 숫자를 쓰지 않는다. 내부 번호도 보이지 않는다", () => {
    render(<IncidentTable incidents={[base]} />);
    expect(screen.getByText("2026-11-02")).toBeInTheDocument();
    expect(screen.queryByText(/D-/)).toBeNull();
    expect(screen.queryByText("#9")).toBeNull();
  });

  it("제출 완료면 기한을 숨기고, 기한이 지났으면 n일 경과", () => {
    render(
      <IncidentTable
        incidents={[
          { ...base, id: 1, reportStatus: "SUBMITTED", reportStatusLabel: "제출 완료" },
          { ...base, id: 2, reportStatus: "OVERDUE", reportStatusLabel: "기한 경과", reportDueDate: "2026-09-15", daysRemaining: -17 },
        ]}
      />,
    );
    expect(screen.queryByText("2026-11-02")).toBeNull();
    expect(screen.getByText("17일 경과")).toBeInTheDocument();
  });

  it("행을 누르면 그 사고를 연다", () => {
    const onOpen = vi.fn();
    render(<IncidentTable incidents={[base]} onOpen={onOpen} />);
    fireEvent.click(screen.getByText("이동식 사다리 A"));
    expect(onOpen).toHaveBeenCalledWith(1);
  });
});
