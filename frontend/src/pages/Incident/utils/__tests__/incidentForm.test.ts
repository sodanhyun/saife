import { describe, expect, it } from "vitest";

import { defaultIncidentForm, toRegisterRequest, validateIncidentForm, type IncidentFormState } from "@/pages/Incident/utils/incidentForm";

const filled: IncidentFormState = {
  ...defaultIncidentForm(),
  equipmentId: "3",
  occurredAt: "2026-10-02T10:20",
  incidentType: "FALL",
  severity: "LOST_TIME",
  leaveDays: "5",
};

describe("defaultIncidentForm", () => {
  it("설비, 발생형태, 재해 정도는 기본값이 없다", () => {
    const f = defaultIncidentForm();
    expect(f.equipmentId).toBe("");
    expect(f.incidentType).toBe("");
    expect(f.severity).toBe("");
  });
});

describe("validateIncidentForm", () => {
  const now = "2026-10-07T08:30";

  it("빈 폼은 설비, 발생형태, 재해 정도가 필요하다", () => {
    const e = validateIncidentForm({ ...defaultIncidentForm(), occurredAt: "2026-10-02T10:20" }, now);
    expect(Object.keys(e).sort()).toEqual(["equipmentId", "incidentType", "severity"]);
  });

  it("휴업이면 휴업예상일수(1 이상)가 필요하다", () => {
    expect(validateIncidentForm({ ...filled, leaveDays: "" }, now).leaveDays).toBeDefined();
    expect(validateIncidentForm({ ...filled, leaveDays: "0" }, now).leaveDays).toBeDefined();
    expect(validateIncidentForm(filled, now)).toEqual({});
    expect(validateIncidentForm({ ...filled, severity: "INJURY", leaveDays: "" }, now)).toEqual({});
  });

  it("발생 일시는 지금보다 늦을 수 없다", () => {
    expect(validateIncidentForm({ ...filled, occurredAt: "2026-10-07T09:00" }, now).occurredAt).toBe("지금보다 늦을 수 없습니다");
  });
});

describe("toRegisterRequest", () => {
  it("휴업일수 빈 문자열은 null이다(0으로 바꾸지 않는다, 미입력은 '모른다')", () => {
    const req = toRegisterRequest({ ...filled, severity: "INJURY", leaveDays: "" });
    expect(req.leaveDays).toBeNull();
  });
  it("설비, 작업계획서 미선택은 null, 선택은 숫자", () => {
    const req = toRegisterRequest({ ...filled, equipmentId: "3", workPlanId: "" });
    expect(req.equipmentId).toBe(3);
    expect(req.workPlanId).toBeNull();
    expect(toRegisterRequest({ ...filled, equipmentId: "" }).equipmentId).toBeNull();
  });
  it("발생형태와 상해 칸을 싣고, 아차사고는 휴업일수와 상해 칸을 보내지 않는다", () => {
    const req = toRegisterRequest({ ...filled, injuryType: " 골절 ", injuryPart: "왼쪽 발목" });
    expect(req.incidentType).toBe("FALL");
    expect(req.injuryType).toBe("골절");
    expect(req.injuryPart).toBe("왼쪽 발목");
    const nm = toRegisterRequest({ ...filled, severity: "NEAR_MISS", leaveDays: "2", injuryType: "골절" });
    expect(nm.leaveDays).toBeNull();
    expect(nm.injuryType).toBeNull();
  });
  it("발생 일시에 오프셋을 붙인다", () => {
    const req = toRegisterRequest({ ...filled, occurredAt: "2026-09-21T06:41" });
    expect(req.occurredAt).toMatch(/^2026-09-21T06:41:00[+-]\d{2}:\d{2}$/);
  });
});
