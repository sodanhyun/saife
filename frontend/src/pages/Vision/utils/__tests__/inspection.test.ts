import { describe, expect, it } from "vitest";

import { dueLabel } from "@/pages/Vision/utils/dates";
import { addParticipants, isComplete } from "@/pages/Vision/utils/inspection";
import type { ActionView } from "@/types/action";
import type { VisionCandidate } from "@/types/vision";

const base: VisionCandidate = {
  hazardId: 1, accidentType: "FALL", accidentLabel: "떨어짐", missingControl: "m", evidence: null, confidence: null,
  riskLevel: "HIGH", ruleTrace: "r", adopted: null, alreadyKnown: false, gateStatus: "PHOTO", acceptable: false,
  suggestedAction: null, action: null, priorOpenAction: null, box: null,
};
const action = { id: 1, status: "PENDING", dueDate: "2099-01-01" } as ActionView;
const overdue = { id: 2, status: "OVERDUE", dueDate: "2020-01-01" } as ActionView;

describe("isComplete", () => {
  it("참여 근로자가 없으면 작성 중이다", () => {
    expect(isComplete([], [{ ...base, adopted: false }])).toBe(false);
  });

  it("판단 전 위험요인이 있으면 작성 중이다", () => {
    expect(isComplete(["김철수"], [base])).toBe(false);
  });

  it("반영했고 허용 불가인데 개선대책이 없으면 작성 중이다", () => {
    expect(isComplete(["김철수"], [{ ...base, adopted: true }])).toBe(false);
  });

  it("개선대책이 있거나, 허용 가능이거나, 제외했으면 확정이다", () => {
    expect(isComplete(["김철수"], [
      { ...base, adopted: true, action },
      { ...base, hazardId: 2, adopted: true, acceptable: true },
      { ...base, hazardId: 3, adopted: false },
      { ...base, hazardId: 4, adopted: true, priorOpenAction: action },
    ])).toBe(true);
  });
});

describe("isComplete 기한 지난 기존 조치", () => {
  it("기한 지난 기존 조치만 있으면 대책을 다시 세워야 하므로 작성 중이다", () => {
    expect(isComplete(["김철수"], [{ ...base, adopted: true, priorOpenAction: overdue }])).toBe(false);
    expect(isComplete(["김철수"], [{ ...base, adopted: true, priorOpenAction: overdue, action }])).toBe(true);
  });
});

describe("addParticipants", () => {
  it("쉼표로 여러 명을 나누고 중복과 빈 이름은 버린다", () => {
    expect(addParticipants(["김철수"], "박민수, 김철수,, 최영희 ")).toEqual(["김철수", "박민수", "최영희"]);
  });
});

describe("dueLabel", () => {
  it("지난 기한은 경과일, 남은 기한은 D-n, 당일은 오늘", () => {
    expect(dueLabel(-30)).toBe("30일 경과");
    expect(dueLabel(3)).toBe("D-3");
    expect(dueLabel(0)).toBe("오늘");
    expect(dueLabel(null)).toBeNull();
  });
});
