import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import WorkPlanResultCard from "@/pages/WorkPlan/components/WorkPlanResultCard";
import { ladderDetail as detail } from "@/pages/WorkPlan/components/__tests__/fixtures";
import type { Evidence } from "@/types/evidence";

const kase = (no: number, thumb: string | null): Evidence => ({
  no, kind: "CASE_DISASTER", refId: no, refKey: `C:${no}`, title: `사례 ${no}`, snippet: "",
  sourceUrl: null, mediaUrl: thumb, thumbnailUrl: thumb, origin: "CACHE", score: 0.5,
  fetchedAt: "2026-09-28T00:00:00+09:00", meta: {},
});

describe("WorkPlanResultCard", () => {
  it("문서 이름은 작업 전 안전점검표이고 내부 번호를 보이지 않는다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByText("작업 전 안전점검표 (TBM)")).toBeInTheDocument();
    expect(screen.queryByText(/#31/)).toBeNull();
    expect(screen.getByText("승인 대기")).toBeInTheDocument();
  });

  it("등급 배지 옆에 평문 근거와 개선대책을 보인다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByLabelText("위험성 상")).toBeInTheDocument();
    expect(screen.getByText(/최상부 발판 또는 그 하단 디딤대 사용/)).toBeInTheDocument();
    expect(screen.getByText("이동식 비계(안전난간) 또는 말비계로 작업발판 확보 (제42조제1항)")).toBeInTheDocument();
    expect(screen.queryByText(/빈도/)).toBeNull();
  });

  it("미이행 조치는 한 번만, 기한 날짜와 n일 경과로 보인다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getAllByText("차양부 천장 작업 시 이동식 비계(안전난간) 사용")).toHaveLength(1);
    expect(screen.getByText("기한 09-02")).toBeInTheDocument();
    expect(screen.getByText("30일 경과")).toBeInTheDocument();
  });

  it("TBM 위험 포인트와 지킬 것, 작업 중지 줄을 보인다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByText("위험 포인트")).toBeInTheDocument();
    expect(screen.getByText("지킬 것")).toBeInTheDocument();
    expect(screen.getByText("위험하면 작업을 멈추고 관리감독자에게 알립니다.")).toBeInTheDocument();
  });

  it("주성분을 추정했으면 추정이라고 밝힌다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByText("추정 주성분 톨루엔")).toBeInTheDocument();
    expect(screen.getByText("제품 MSDS 확인 필요")).toBeInTheDocument();
    expect(screen.getByText("H225 고인화성 액체 및 증기")).toBeInTheDocument();
  });

  it("현장 확인 값은 단위를 붙여 보인다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByText("발판 높이")).toBeInTheDocument();
    expect(screen.getByText("3.2 m")).toBeInTheDocument();
  });

  it("사진 있는 사례만 썸네일, 사진 없는 사례는 글 목록으로 보이고 빈 회색 상자를 그리지 않는다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[kase(1, "/media/1.jpg"), kase(2, null)]} onOpenDetail={() => {}} />);
    expect(screen.getAllByRole("img")).toHaveLength(1);
    expect(screen.getByText("사례 2")).toBeInTheDocument();
    expect(screen.queryByText("#2")).toBeNull();
  });

  it("결과 카드 버튼은 검토 및 승인, 서식 출력이다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByRole("button", { name: "검토 및 승인" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "서식 출력" })).toHaveAttribute("target", "_blank");
  });

  it("승인되면 머리에 승인자와 시각을 보인다", () => {
    render(<WorkPlanResultCard detail={{ ...detail, status: "CONDITIONAL", approvedBy: "김철수", approvedAt: "2026-10-02T08:50:00+09:00", approvalNote: "2인 1조, 맨 위 두 칸 사용 금지" }} evidence={[]} />);
    expect(screen.getByText("조건부 승인 김철수, 10-02 08:50")).toBeInTheDocument();
    expect(screen.getByText("2인 1조, 맨 위 두 칸 사용 금지")).toBeInTheDocument();
  });

  it("승인 후 TBM 전이면 바닥 주 버튼은 TBM 실시다", () => {
    render(<WorkPlanResultCard detail={{ ...detail, status: "APPROVED", approvedBy: "김철수", approvedAt: "2026-10-02T08:50:00+09:00" }} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByRole("button", { name: "TBM 실시" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "검토 및 승인" })).toBeNull();
  });

  it("작업 보류면 수시평가로 가고, 보류 전 승인 시각은 따로 말한다", () => {
    render(
      <MemoryRouter>
        <WorkPlanResultCard detail={{ ...detail, status: "HOLD", holdAssessmentId: 77, approvedBy: "최동훈", approvedAt: "2026-10-03T08:00:00+09:00",
          warningNote: "작업 보류: 수시평가 완료 전 작업 재개 금지" }} evidence={[]} onOpenDetail={() => {}} />
      </MemoryRouter>,
    );
    expect(screen.getByRole("link", { name: "수시평가" })).toHaveAttribute("href", "/assessment/77");
    expect(screen.getByText("보류 전 승인 최동훈, 10-03 08:00")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "검토 및 승인" })).toBeNull();
  });

  it("작업계획서는 작업지휘자와 사전조사를 보인다", () => {
    const base = detail.briefingView!;
    render(<WorkPlanResultCard detail={{ ...detail, documentType: "WORK_PLAN", documentTitle: "작업계획서", supervisor: "정민준",
      briefingView: { ...base, preSurvey: ["인양물 중량과 무게중심"] } }} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByText("작업지휘자")).toBeInTheDocument();
    expect(screen.getByText("정민준")).toBeInTheDocument();
    expect(screen.getByText("사전조사")).toBeInTheDocument();
    expect(screen.getByText("인양물 중량과 무게중심")).toBeInTheDocument();
  });

  it("사례 제목의 꼬리표와 관리번호를 떼고 표기를 바로잡는다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[{ ...kase(3, null), title: "[6/19, 경남 거제시] [사망 1명] 작업중 알콜 증기 화재 (200903)" }]} onOpenDetail={() => {}} />);
    expect(screen.getByText("작업 중 알코올 증기 화재")).toBeInTheDocument();
  });

  it("현장 확인 값이 없으면 현장 확인 절을 그리지 않는다", () => {
    render(<WorkPlanResultCard detail={{ ...detail, slots: [] }} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.queryByText("현장 확인")).toBeNull();
  });
});
