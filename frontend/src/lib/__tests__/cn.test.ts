import { describe, expect, it } from "vitest";

import cn from "@/lib/cn";

describe("cn", () => {
  it("뒤에 온 같은 계열 클래스가 앞을 덮는다(tailwind-merge)", () => {
    expect(cn("px-3 py-2", "px-5")).toBe("py-2 px-5");
  });

  it("falsy 값은 버린다(clsx)", () => {
    // eslint no-constant-binary-expression 회피 — 리터럴 대신 변수로 조건부 클래스를 흉내낸다.
    const disabled = false;
    expect(cn("a", disabled && "b", undefined, "c")).toBe("a c");
  });
});
