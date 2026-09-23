import { describe, expect, it } from "vitest";

import { defaultIncidentForm, toRegisterRequest } from "@/pages/Incident/utils/incidentForm";

describe("toRegisterRequest", () => {
  it("휴업일수 빈 문자열은 null이다(0으로 바꾸지 않는다 — 미입력은 '모른다')", () => {
    const req = toRegisterRequest({ ...defaultIncidentForm(), leaveDays: "" });
    expect(req.leaveDays).toBeNull();
  });
  it("설비·작업계획서 미선택은 null, 선택은 숫자", () => {
    const req = toRegisterRequest({ ...defaultIncidentForm(), equipmentId: "3", workPlanId: "" });
    expect(req.equipmentId).toBe(3);
    expect(req.workPlanId).toBeNull();
  });
  it("발생 일시에 오프셋을 붙인다", () => {
    const req = toRegisterRequest({ ...defaultIncidentForm(), occurredAt: "2026-09-21T06:41" });
    expect(req.occurredAt).toMatch(/^2026-09-21T06:41:00[+-]\d{2}:\d{2}$/);
  });
});
