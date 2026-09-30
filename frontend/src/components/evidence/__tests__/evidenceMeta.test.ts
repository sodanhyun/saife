import { describe, expect, it } from "vitest";

import { originLabel, originTone, scoreLabel } from "@/components/evidence/evidenceMeta";
import type { Evidence } from "@/types/evidence";

function ev(overrides: Partial<Evidence> = {}): Evidence {
  return {
    no: 1,
    kind: "GUIDE",
    refId: 1,
    refKey: "G:1",
    title: "제목",
    snippet: "",
    sourceUrl: null,
    mediaUrl: null,
    thumbnailUrl: null,
    origin: "CACHE",
    score: 0.5,
    fetchedAt: "2026-09-28T00:00:00+09:00",
    meta: {},
    ...overrides,
  };
}

describe("evidenceMeta.scoreLabel", () => {
  it("LAW·MSDS는 유사도를 보여주지 않는다", () => {
    expect(scoreLabel(ev({ kind: "LAW" }))).toBeNull();
    expect(scoreLabel(ev({ kind: "MSDS" }))).toBeNull();
  });

  it("KEYWORD_FALLBACK은 가짜 유사도를 숨긴다 (F2/R49)", () => {
    expect(scoreLabel(ev({ origin: "KEYWORD_FALLBACK", score: 0.42 }))).toBeNull();
  });

  it("정상 범위는 반올림해 퍼센트로 보여준다", () => {
    expect(scoreLabel(ev({ score: 0.84 }))).toBe("유사도 84%");
  });

  it("부스트로 1.0을 넘는 점수는 100%로 clamp한다 (F2)", () => {
    expect(scoreLabel(ev({ score: 1.07 }))).toBe("유사도 100%");
    expect(scoreLabel(ev({ score: 1.5 }))).toBe("유사도 100%");
  });
});

describe("evidenceMeta.originLabel/originTone", () => {
  it("LIVE는 조회 시각을, CACHE는 캐시 날짜를 보여준다", () => {
    expect(originLabel(ev({ origin: "LIVE", fetchedAt: "2026-09-28T14:02:00+09:00" }))).toBe("실시간 조회 14:02");
    expect(originLabel(ev({ origin: "CACHE", fetchedAt: "2026-09-21T10:00:00+09:00" }))).toBe("캐시 09-21");
  });

  it("KEYWORD_FALLBACK은 고정 문구다", () => {
    expect(originLabel(ev({ origin: "KEYWORD_FALLBACK" }))).toBe("키워드 검색");
  });

  it("톤은 origin별로 갈린다", () => {
    expect(originTone(ev({ origin: "LIVE" }))).toBe("progress");
    expect(originTone(ev({ origin: "CACHE" }))).toBe("neutral");
    expect(originTone(ev({ origin: "KEYWORD_FALLBACK" }))).toBe("pending");
  });
});
