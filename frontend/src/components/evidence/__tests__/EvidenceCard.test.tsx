import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import EvidenceCard from "@/components/evidence/EvidenceCard";
import type { Evidence } from "@/types/evidence";

const base: Evidence = {
  no: 3, kind: "CASE_FATALITY", refId: 1, refKey: "F:1", title: "[협착] 스크류에 끼임", snippet: "설비 내 슬러지 제거 중 …",
  sourceUrl: null, mediaUrl: "/api/media/case/1/photo", thumbnailUrl: "/api/media/case/1/photo?w=320",
  origin: "CACHE", score: 0.84, fetchedAt: "2026-09-21T10:00:00+09:00", meta: { hasImage: true },
};

describe("EvidenceCard", () => {
  it("번호·제목·발췌·유사도·origin을 그린다", () => {
    render(<EvidenceCard e={base} scope="chat" />);
    expect(screen.getByText("#3")).toBeInTheDocument();
    expect(screen.getByText("[협착] 스크류에 끼임")).toBeInTheDocument();
    expect(screen.getByText(/유사도 84%/)).toBeInTheDocument();
    expect(screen.getByText(/캐시 09-21/)).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "[협착] 스크류에 끼임" })).toHaveAttribute("src", "/api/media/case/1/photo?w=320");
    expect(document.getElementById("evidence-chat-3")).not.toBeNull();
  });

  it("origin 배지 — KEYWORD_FALLBACK은 유사도도 숨긴다(R49)", () => {
    const { rerender } = render(<EvidenceCard e={{ ...base, origin: "LIVE", fetchedAt: "2026-09-28T14:02:00+09:00" }} scope="chat" />);
    expect(screen.getByText(/실시간 조회 14:02/)).toBeInTheDocument();
    rerender(<EvidenceCard e={{ ...base, origin: "KEYWORD_FALLBACK" }} scope="chat" />);
    expect(screen.getByText("키워드 검색")).toBeInTheDocument();
    expect(screen.queryByText(/유사도/)).toBeNull();
  });

  it("사진 실패 degrade — 제목과 원문 링크는 남는다", () => {
    render(<EvidenceCard e={{ ...base, sourceUrl: "https://portal.kosha.or.kr/x" }} scope="chat" />);
    fireEvent.error(screen.getByRole("img"));
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.getByText("[협착] 스크류에 끼임")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /원문 보기/ })).toHaveAttribute("href", "https://portal.kosha.or.kr/x");
  });

  it("썸네일 클릭이 onOpenPhoto를 부른다", () => {
    const onOpen = vi.fn();
    render(<EvidenceCard e={base} scope="chat" onOpenPhoto={onOpen} />);
    fireEvent.click(screen.getByRole("button", { name: /사진 크게 보기/ }));
    expect(onOpen).toHaveBeenCalledWith(base);
  });

  it("법 조문은 LawArticleCard로", () => {
    render(<EvidenceCard e={{ ...base, kind: "LAW", mediaUrl: null, thumbnailUrl: null, title: "산업안전보건법 제36조(위험성평가의 실시) — 위험성평가의 실시", snippet: "① 사업주는 …", sourceUrl: "https://www.law.go.kr/x", meta: { effectiveOn: "2026-06-01", fullText: "① 사업주는 … 전체" } }} scope="chat" />);
    expect(screen.getByText(/시행 2026-06-01/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /법제처/ })).toBeInTheDocument();
    expect(document.getElementById("evidence-chat-3")).not.toBeNull();
  });

  it("scope가 다르면 id도 달라진다(F18)", () => {
    const { rerender } = render(<EvidenceCard e={base} scope="chat" />);
    expect(document.getElementById("evidence-chat-3")).not.toBeNull();
    rerender(<EvidenceCard e={base} scope="incident" />);
    expect(document.getElementById("evidence-chat-3")).toBeNull();
    expect(document.getElementById("evidence-incident-3")).not.toBeNull();
  });
});
