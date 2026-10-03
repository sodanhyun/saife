import { describe, expect, it } from "vitest";

import { actionRow } from "@/pages/Action/__tests__/fixtures";
import { dueInfo, emptyMessage, filterCount, filterLabel, hazardLine, parseFilter } from "@/pages/Action/utils/actionRow";

const TODAY = "2026-10-03";
const counts = { open: 9, overdue: 3, done: 45 };

describe("actionRow", () => {
  it("탭 라벨은 이름과 건수, 전체는 건수 없음", () => {
    expect(filterLabel("OPEN", counts)).toBe("미이행 9");
    expect(filterLabel("OVERDUE", counts)).toBe("기한 경과 3");
    expect(filterLabel("DONE", counts)).toBe("완료 45");
    expect(filterLabel("ALL", counts)).toBe("전체");
    expect(filterLabel("OPEN", null)).toBe("미이행");
    expect(filterCount("ALL", counts)).toBeNull();
  });

  it("URL status는 대소문자 무관, 모르는 값은 미이행", () => {
    expect(parseFilter("OVERDUE")).toBe("OVERDUE");
    expect(parseFilter("done")).toBe("DONE");
    expect(parseFilter("late")).toBe("OPEN");
    expect(parseFilter(null)).toBe("OPEN");
  });

  it("빈 상태 문구", () => {
    expect(emptyMessage("OPEN", false)).toBe("미이행 개선대책 없음");
    expect(emptyMessage("OVERDUE", false)).toBe("기한 경과 개선대책 없음");
    expect(emptyMessage("ALL", false)).toBe("개선대책 없음");
    expect(emptyMessage("DONE", true)).toBe("검색 결과 없음");
  });

  it("기한 경과는 N일 경과", () => {
    expect(dueInfo(actionRow({ status: "OVERDUE", dueDate: "2026-09-03", overdueDays: 30 }), TODAY)).toEqual({
      date: "2026-09-03",
      hint: "30일 경과",
      tone: "overdue",
    });
  });

  it("7일 이내는 D-N, 당일은 오늘, 그 뒤는 표기 없음", () => {
    expect(dueInfo(actionRow({ dueDate: "2026-10-06" }), TODAY)).toMatchObject({ hint: "D-3", tone: "soon" });
    expect(dueInfo(actionRow({ dueDate: "2026-10-10" }), TODAY)).toMatchObject({ hint: "D-7", tone: "soon" });
    expect(dueInfo(actionRow({ dueDate: TODAY }), TODAY)).toMatchObject({ hint: "오늘", tone: "soon" });
    expect(dueInfo(actionRow({ dueDate: "2026-10-11" }), TODAY)).toMatchObject({ hint: null, tone: "normal" });
    expect(dueInfo(actionRow({ dueDate: null }), TODAY)).toMatchObject({ date: "-", hint: null, tone: "none" });
  });

  it("완료는 완료일(KST)", () => {
    const d = dueInfo(actionRow({ status: "DONE", completedAt: "2026-09-30T16:30:00Z" }), TODAY);
    expect(d).toEqual({ date: "2026-10-01", hint: "완료일", tone: "done" });
  });

  it("둘째 줄은 발생형태, 빠진 안전조치", () => {
    expect(hazardLine(actionRow())).toBe("떨어짐, 작업발판 미확보");
    expect(hazardLine(actionRow({ missingControl: null }))).toBe("떨어짐");
    expect(hazardLine(actionRow({ accidentType: null, missingControl: " " }))).toBeNull();
  });

  it("화면 문자열에 가운뎃점, 대시를 쓰지 않는다", () => {
    const strings = [
      ...(["OPEN", "OVERDUE", "DONE", "ALL"] as const).map((f) => filterLabel(f, counts)),
      hazardLine(actionRow()),
      dueInfo(actionRow({ dueDate: "2026-10-06" }), TODAY).hint,
    ].join(" ");
    expect(strings).not.toMatch(/[·ㆍ—–]| - /);
  });
});
