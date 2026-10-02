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
  it("제목(원문 머리표 제거), 발췌, 사진을 그리고 내부 번호, 출처 상태, 유사도는 보이지 않는다", () => {
    render(<EvidenceCard e={base} scope="chat" />);
    expect(screen.queryByText("#3")).toBeNull();
    expect(screen.queryByText(/#\d/)).toBeNull();
    expect(screen.getByText("스크류에 끼임")).toBeInTheDocument();
    expect(screen.queryByText(/유사도/)).toBeNull();
    expect(screen.queryByText(/캐시/)).toBeNull();
    expect(screen.getByRole("img", { name: "스크류에 끼임" })).toHaveAttribute("src", "/api/media/case/1/photo?w=320");
    expect(document.getElementById("evidence-chat-3")).not.toBeNull();
  });

  it("사진 실패 degrade — 제목과 원문 링크는 남는다", () => {
    render(<EvidenceCard e={{ ...base, sourceUrl: "https://portal.kosha.or.kr/x" }} scope="chat" />);
    fireEvent.error(screen.getByRole("img"));
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.getByText("스크류에 끼임")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /원문 보기/ })).toHaveAttribute("href", "https://portal.kosha.or.kr/x");
  });

  it("썸네일 클릭이 onOpenPhoto를 부른다", () => {
    const onOpen = vi.fn();
    render(<EvidenceCard e={base} scope="chat" onOpenPhoto={onOpen} />);
    fireEvent.click(screen.getByRole("button", { name: /사진 크게 보기/ }));
    expect(onOpen).toHaveBeenCalledWith(base);
  });

  it("사례 제목의 지역 날짜 머리표와 연월 코드, 발췌 끝 null을 지운다", () => {
    render(<EvidenceCard e={{ ...base, title: "[추락] [6/19, 경남 거제시] 사다리에서 떨어짐 (200903)", snippet: "사다리에서 떨어짐 null" }} scope="chat" />);
    expect(screen.getByText("사다리에서 떨어짐", { selector: "p.text-stage" })).toBeInTheDocument();
    expect(screen.queryByText(/null/)).toBeNull();
    expect(screen.queryByText(/거제시/)).toBeNull();
  });

  it("조문 카드는 인용한 항호를 발췌로 보이고 그 표시를 단다", () => {
    render(<EvidenceCard e={{ ...base, kind: "LAW", mediaUrl: null, thumbnailUrl: null, title: "시행규칙 제37조: 위험성평가의 방법, 절차 및 시기", snippet: "② 사업주는 다음 각 호의 구분에 따라 위험성평가를 실시해야 한다.\n3. 수시평가: 산업재해가 발생한 경우 관련 작업을 시작하기 전까지", sourceUrl: null, meta: { focus: "제2항제3호", fullText: "① …\n② …" } }} scope="incident" />);
    expect(screen.getByText("제2항제3호")).toBeInTheDocument();
    expect(screen.getByText(/관련 작업을 시작하기 전까지/)).toBeInTheDocument();
    expect(screen.queryByText(/#3/)).toBeNull();
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
