import { describe, expect, it } from "vitest";

import { plainText } from "@/utils/plainText";

describe("plainText", () => {
  it("대시와 가운뎃점을 화면 표기로 바꾼다", () => {
    expect(plainText("A — B")).toBe("A, B");
    expect(plainText("A · B")).toBe("A, B");
    expect(plainText("절단·가공")).toBe("절단/가공");
    expect(plainText("위험성평가 결과의 기록ㆍ보존")).toBe("위험성평가 결과의 기록/보존");
  });

  it("끝에 남은 대시는 지운다", () => {
    expect(plainText("이동식 사다리의 사용에 관한 기술지원규정 — ")).toBe("이동식 사다리의 사용에 관한 기술지원규정");
  });
});
