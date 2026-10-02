import { describe, expect, it } from "vitest";

import { cleanEvidenceSnippet, cleanEvidenceTitle, originLabel, originTone, scoreLabel } from "@/components/evidence/evidenceMeta";
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
  it("LIVE는 조회 시각을, CACHE는 기준일을 보여주고 개발 용어(캐시, 실시간)를 쓰지 않는다", () => {
    expect(originLabel(ev({ origin: "LIVE", fetchedAt: "2026-09-28T14:02:00+09:00" }))).toBe("14:02 조회");
    expect(originLabel(ev({ origin: "CACHE", fetchedAt: "2026-09-21T10:00:00+09:00" }))).toBe("09-21 기준");
    expect(originLabel(ev({ origin: "CACHE" }))).not.toMatch(/캐시|실시간/);
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

describe("evidenceMeta.cleanEvidenceTitle/cleanEvidenceSnippet", () => {
  it("원문 머리표, 지역 날짜 머리표, 사망자 수, 연월 코드를 떼고 표기를 고친다", () => {
    expect(cleanEvidenceTitle("[추락] [제조업] [6/19, 경남 거제시] 사다리에서 떨어짐 (200903)")).toBe("사다리에서 떨어짐");
    expect(cleanEvidenceTitle("[사망 1명] 작업중 알콜 증기 화재")).toBe("작업 중 알코올 증기 화재");
  });

  it("머리표뿐인 제목은 원문을 그대로 둔다", () => {
    expect(cleanEvidenceTitle("[추락]")).toBe("[추락]");
  });

  it("발췌 끝의 null 흔적을 지운다", () => {
    expect(cleanEvidenceSnippet("스크류 콘베이어를 점검하던중 협착 null")).toBe("스크류 콘베이어를 점검하던중 협착");
    expect(cleanEvidenceSnippet(null)).toBe("");
  });
});
