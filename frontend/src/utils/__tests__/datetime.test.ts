import { describe, expect, it } from "vitest";

import { dDayLabel, formatDate, formatDateTime, toOffsetIso } from "@/utils/datetime";

describe("datetime", () => {
  it("date-only는 변환 없이 그대로 둔다(경계에서 하루 밀림 방지)", () => {
    expect(formatDate("2026-09-21")).toBe("2026-09-21");
  });
  it("Instant는 Asia/Seoul로 고정해 표시한다", () => {
    expect(formatDateTime("2026-09-20T21:41:00Z")).toBe("2026-09-21 06:41");
  });
  it("빈 값은 대시", () => {
    expect(formatDate(null)).toBe("-");
    expect(formatDateTime(undefined)).toBe("-");
  });
  it("toOffsetIso는 초와 브라우저 오프셋을 붙인다", () => {
    const out = toOffsetIso("2026-09-21T06:41");
    expect(out.startsWith("2026-09-21T06:41:00")).toBe(true);
    expect(out).toMatch(/[+-]\d{2}:\d{2}$/);
  });
  it("D-day 라벨", () => {
    expect(dDayLabel(30)).toBe("D-30");
    expect(dDayLabel(-3)).toBe("D+3");
    expect(dDayLabel(0)).toBe("D-Day");
    expect(dDayLabel(null)).toBe("");
  });
});
