import { describe, expect, it } from "vitest";

import { emphasisTone, reportDutyTone, riskColor, toneColor, workPlanStatusTone } from "@/utils/statusColors";

describe("statusColors — 색 매핑 SSOT", () => {
  it("등급 상은 risk-high 연톤 칩이다", () => {
    expect(riskColor("HIGH").chip).toBe("bg-risk-high-bg text-risk-high-text border-risk-high-border");
  });

  it("neutral은 slate 무채색이다(정상 상태는 색을 갖지 않는다)", () => {
    expect(toneColor("neutral").chip).toBe("bg-slate-100 text-slate-700 border-slate-200");
  });

  it("승인 대기는 pending, 반려는 high, 승인은 neutral", () => {
    expect(workPlanStatusTone("SUBMITTED")).toBe("pending");
    expect(workPlanStatusTone("REJECTED")).toBe("high");
    expect(workPlanStatusTone("APPROVED")).toBe("neutral");
  });

  it("타임라인 강조도 CRITICAL→high, WARNING→pending", () => {
    expect(emphasisTone("CRITICAL")).toBe("high");
    expect(emphasisTone("WARNING")).toBe("pending");
    expect(emphasisTone("NORMAL")).toBe("neutral");
  });

  it("제출 기한 OVERDUE→high, REQUIRED→pending", () => {
    expect(reportDutyTone("OVERDUE")).toBe("high");
    expect(reportDutyTone("REQUIRED")).toBe("pending");
    expect(reportDutyTone("SUBMITTED")).toBe("neutral");
  });
});
