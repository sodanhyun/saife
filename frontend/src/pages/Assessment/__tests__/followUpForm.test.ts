import { describe, expect, it } from "vitest";

import { followUpFixture } from "@/pages/Assessment/__tests__/fixtures";
import {
  defaultDue,
  hasErrors,
  initialFollowUpForm,
  needsPlan,
  toFollowUpRequest,
  validateFollowUp,
} from "@/pages/Assessment/utils/followUpForm";

const TODAY = "2026-10-07";

describe("initialFollowUpForm", () => {
  it("허용 불가 위험요인은 기준표 대책과 기존 담당으로, 기한은 오늘부터 14일로 채운다", () => {
    const f = initialFollowUpForm(followUpFixture(), TODAY);
    expect(f.hazards[1]).toEqual({
      acceptable: false,
      content: "이동식 비계(안전난간) 또는 말비계로 작업발판 확보",
      owner: "생산반장 김철수",
      dueDate: defaultDue(TODAY),
    });
    expect(defaultDue(TODAY)).toBe("2026-10-21");
    expect(f.hazards[2].acceptable).toBe(true);
    expect(f.participants).toEqual([]);
  });
});

describe("validateFollowUp", () => {
  it("참여 근로자가 없으면 오류", () => {
    const d = followUpFixture();
    const e = validateFollowUp(d, initialFollowUpForm(d, TODAY), TODAY);
    expect(e.participants).toBe("참여 근로자를 입력하십시오");
  });

  it("허용 불가인데 기존 대책 기한이 지났으면 새 대책(무엇을, 담당, 기한)이 필요하다", () => {
    const d = followUpFixture();
    const f = { ...initialFollowUpForm(d, TODAY), participants: ["김철수"] };
    expect(needsPlan(d.hazards[0], f.hazards[1], TODAY)).toBe(true);
    const e = validateFollowUp(d, { ...f, hazards: { ...f.hazards, 1: { ...f.hazards[1], owner: "", dueDate: "2026-10-01" } } }, TODAY);
    expect(e.hazards[1]).toEqual({ owner: "담당을 입력하십시오", dueDate: "오늘 이후로" });
    expect(hasErrors(validateFollowUp(d, f, TODAY))).toBe(false);
  });

  it("허용 가능으로 바꾸면 대책이 필요 없다", () => {
    const d = followUpFixture();
    const f = initialFollowUpForm(d, TODAY);
    expect(needsPlan(d.hazards[0], { ...f.hazards[1], acceptable: true }, TODAY)).toBe(false);
  });
});

describe("toFollowUpRequest", () => {
  it("허용 불가이고 이번 대책이 없을 때만 대책 칸을 보낸다", () => {
    const d = followUpFixture();
    const f = { ...initialFollowUpForm(d, TODAY), inspector: " 홍길동 ", participants: ["김철수", "이영희"] };
    const req = toFollowUpRequest(d, f);
    expect(req.inspector).toBe("홍길동");
    expect(req.participants).toEqual(["김철수", "이영희"]);
    expect(req.hazards[0]).toEqual({
      hazardId: 1,
      acceptable: false,
      content: "이동식 비계(안전난간) 또는 말비계로 작업발판 확보",
      owner: "생산반장 김철수",
      dueDate: "2026-10-21",
    });
    expect(req.hazards[1]).toEqual({ hazardId: 2, acceptable: true, content: null, owner: null, dueDate: null });
  });
});
