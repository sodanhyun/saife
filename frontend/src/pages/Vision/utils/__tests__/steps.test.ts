import { describe, expect, it } from "vitest";

import { deriveSteps } from "@/pages/Vision/utils/steps";
import type { ActionView } from "@/types/action";
import type { VisionAnalysisResult, VisionCandidate } from "@/types/vision";

const base: VisionCandidate = {
  hazardId: 1, accidentType: "PPE", accidentLabel: "보호구", missingControl: "안전대 미착용", evidence: null,
  confidence: null, riskLevel: "HIGH", ruleTrace: "r", adopted: null, alreadyKnown: false, gateStatus: "PHOTO",
  gateNote: null, suggestedAction: null, action: null, priorOpenAction: null,
};
const act = (status: ActionView["status"]): ActionView => ({
  id: 5, hazardId: 1, assessmentId: 36, equipmentId: 1, content: "x", owner: null, dueDate: null, status,
  guideRef: null, completedAt: null, createdAt: "t",
});
const result = (candidates: VisionCandidate[]): VisionAnalysisResult => ({ assessmentId: 36, status: "ANALYZED", candidates, demoMode: false });
const states = (r: ReturnType<typeof deriveSteps>) => r.map((s) => s.state);

describe("deriveSteps", () => {
  it("사진 전에는 1단계가 지금 여기다", () => {
    expect(states(deriveSteps({ hasPhoto: false, analyzing: false, result: null, progress: null })))
      .toEqual(["active", "idle", "idle", "idle", "idle"]);
  });

  it("판독 중이면 2단계가 running이고 진행 문구를 보인다", () => {
    const s = deriveSteps({ hasPhoto: true, analyzing: true, result: null, progress: "모델 판독 중" });
    expect(states(s)).toEqual(["done", "running", "idle", "idle", "idle"]);
    expect(s[1].detail).toBe("모델 판독 중");
  });

  it("채택, 대책 등록, 이행 완료로 단계가 넘어간다", () => {
    const at = (c: VisionCandidate) => states(deriveSteps({ hasPhoto: true, analyzing: false, result: result([c]), progress: null }));
    expect(at(base)).toEqual(["done", "done", "active", "idle", "idle"]);
    expect(at({ ...base, adopted: true })).toEqual(["done", "done", "done", "active", "idle"]);
    expect(at({ ...base, adopted: true, action: act("PENDING") })).toEqual(["done", "done", "done", "done", "active"]);
    expect(at({ ...base, adopted: true, action: act("DONE") })).toEqual(["done", "done", "done", "done", "done"]);
  });

  it("재확인 후보의 기존 미이행 조치는 새 대책을 요구하지 않는다", () => {
    const known = { ...base, hazardId: 2, adopted: true, alreadyKnown: true, priorOpenAction: act("OVERDUE") };
    const s = deriveSteps({ hasPhoto: true, analyzing: false, result: result([known, { ...base, adopted: true, action: act("DONE") }]), progress: null });
    expect(states(s)).toEqual(["done", "done", "done", "done", "done"]);
    expect(s[3].detail).toBe("등록 1/1건");
  });

  it("후보가 0건이면 판독에서 흐름이 끝난다", () => {
    expect(states(deriveSteps({ hasPhoto: true, analyzing: false, result: result([]), progress: null })))
      .toEqual(["done", "done", "idle", "idle", "idle"]);
  });
});
