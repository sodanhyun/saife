import { describe, expect, it } from "vitest";

import { gradeTo, plain } from "@/pages/Incident/utils/premonition";

describe("plain", () => {
  it("가운뎃점, 대시 구분자를 쉼표로 바꾼다", () => {
    expect(plain("D-31 · 제73조")).toBe("D-31, 제73조");
    expect(plain("사고 발생 — 작업 재개 전 확인")).toBe("사고 발생, 작업 재개 전 확인");
    expect(plain("A – B")).toBe("A, B");
    expect(plain("A - B")).toBe("A, B");
  });

  it("하이픈이 든 날짜와 D-day는 건드리지 않는다", () => {
    expect(plain("2026-10-02 D-31")).toBe("2026-10-02 D-31");
    expect(plain(null)).toBe("");
  });
});

describe("gradeTo", () => {
  it("등급 글자의 받침에 맞춰 조사를 붙인다", () => {
    expect(gradeTo("HIGH")).toBe("'상'으로");
    expect(gradeTo("MEDIUM")).toBe("'중'으로");
    expect(gradeTo("LOW")).toBe("'하'로");
  });
});
