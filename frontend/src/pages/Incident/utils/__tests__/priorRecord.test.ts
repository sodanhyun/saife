import { describe, expect, it } from "vitest";

import { elapsedLabel, incidentTitle, plain, reportValue, shortDate } from "@/pages/Incident/utils/priorRecord";
import type { IncidentSummary, ReportDuty, UnfinishedAction } from "@/types/incident";

describe("plain", () => {
  it("가운뎃점, 대시 구분자, 화살표를 쉼표로 바꾸고 따옴표 등급을 푼다", () => {
    expect(plain("제73조 · 1개월")).toBe("제73조, 1개월");
    expect(plain("사고 발생 — 작업 재개 전 확인")).toBe("사고 발생, 작업 재개 전 확인");
    expect(plain("A – B")).toBe("A, B");
    expect(plain("A - B")).toBe("A, B");
    expect(plain("안전대 부착설비 없음 → '상'")).toBe("안전대 부착설비 없음, 상");
  });

  it("하이픈이 든 날짜는 건드리지 않는다", () => {
    expect(plain("2026-10-02 기한")).toBe("2026-10-02 기한");
    expect(plain(null)).toBe("");
  });
});

describe("날짜", () => {
  it("촘촘한 표기는 MM-DD", () => {
    expect(shortDate("2026-11-02")).toBe("11-02");
    expect(shortDate(null)).toBe("-");
  });
});

describe("incidentTitle", () => {
  const inc = {
    incidentType: "FALL",
    incidentTypeLabel: "떨어짐",
    severity: "LOST_TIME",
    equipmentName: "이동식 사다리 A",
    occurredAt: "2026-10-02T01:20:00Z",
  } as IncidentSummary;

  it("발생형태 사고, 설비, 일시(YYYY-MM-DD HH:mm, 한국 시간)", () => {
    expect(incidentTitle(inc)).toBe("떨어짐 사고, 이동식 사다리 A, 2026-10-02 10:20");
  });

  it("아차사고는 '아차사고'로 쓴다", () => {
    expect(incidentTitle({ ...inc, incidentType: "STRUCK", incidentTypeLabel: "부딪힘", severity: "NEAR_MISS" })).toBe(
      "부딪힘 아차사고, 이동식 사다리 A, 2026-10-02 10:20",
    );
  });

  it("라벨이 비어 오면 발생형태 코드로 라벨을 찾는다", () => {
    expect(incidentTitle({ ...inc, incidentType: "CUT", incidentTypeLabel: null })).toBe("절단, 베임, 찔림 사고, 이동식 사다리 A, 2026-10-02 10:20");
  });

  it("발생형태와 설비를 모르면 그대로 말한다", () => {
    expect(incidentTitle({ ...inc, incidentType: null, incidentTypeLabel: null, equipmentName: null })).toBe("사고, 설비 미상, 2026-10-02 10:20");
  });
});

describe("reportValue", () => {
  const duty: ReportDuty = {
    status: "REQUIRED",
    statusLabel: "제출 필요",
    dueDate: "2026-11-02",
    daysRemaining: 30,
    basis: "",
    seriousAccidentPossible: false,
    submittedOn: null,
  };
  it("제출 완료는 제출일, 제출 필요는 기한 날짜, 대상이 아니면 제출 대상 아님", () => {
    expect(reportValue(duty)).toBe("기한 2026-11-02");
    expect(reportValue({ ...duty, status: "SUBMITTED", submittedOn: "2026-10-12" })).toBe("제출 완료 2026-10-12");
    expect(reportValue({ ...duty, status: "NOT_REQUIRED", dueDate: null })).toBe("제출 대상 아님");
  });
});

describe("elapsedLabel", () => {
  const a = { overdueDays: 30 } as UnfinishedAction;
  it("기한이 지났으면 n일 경과, 아니면 기한 전", () => {
    expect(elapsedLabel(a)).toBe("30일 경과");
    expect(elapsedLabel({ ...a, overdueDays: -3 })).toBe("기한 전");
    expect(elapsedLabel({ ...a, overdueDays: null })).toBe("");
  });
});
