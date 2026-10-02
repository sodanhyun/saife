import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";

import IncidentResult from "@/pages/Incident/components/IncidentResult";
import type { AffectedWorkPlan, CascadeStep } from "@/types/incident";
import type { Evidence, EvidenceKind } from "@/types/evidence";
import type { IncidentRegisterResponse } from "@/types/incident";

function ev(no: number, kind: EvidenceKind = "GUIDE"): Evidence {
  return {
    no,
    kind,
    refId: no,
    refKey: `G:${no}`,
    title: `근거 ${no}`,
    snippet: "",
    sourceUrl: null,
    mediaUrl: null,
    thumbnailUrl: null,
    origin: "CACHE",
    score: 0.5,
    fetchedAt: "2026-09-28T00:00:00+09:00",
    meta: {},
  };
}

function step(order: number, kind: CascadeStep["kind"]): CascadeStep {
  return {
    order,
    kind,
    title: `스텝 ${order}`,
    detail: `상세 ${order}`,
    emphasis: "NORMAL",
    refId: null,
    refType: null,
  };
}

function workPlan(id: number): AffectedWorkPlan {
  return { workPlanId: id, workName: "조명 교체", workDate: "2026-09-25", status: "APPROVED", warning: "경고 문구" };
}

const fixture: IncidentRegisterResponse = {
  incident: {
    id: 1,
    siteId: 1,
    equipmentId: 10,
    equipmentName: "프레스 1호기",
    workPlanId: null,
    occurredAt: "2026-09-20T10:00:00+09:00",
    victimName: "홍길동",
    severity: "INJURY",
    leaveDays: 5,
    accidentType: "CAUGHT",
    description: "설명",
    followUpAssessmentId: 100,
  },
  reportDuty: {
    status: "REQUIRED",
    statusLabel: "제출 필요",
    dueDate: "2026-10-20",
    daysRemaining: 20,
    basis: "휴업 3일 이상",
  },
  recall: {
    equipmentId: 10,
    equipmentName: "프레스 1호기",
    locationTag: "공장동",
    headline: "사고 전 경고 있었음",
    predicted: true,
    warnedAt: null,
    priorHazards: [
      {
        hazardId: 7,
        accidentType: "CAUGHT",
        missingControl: "방호덮개 미설치",
        description: null,
        lastRiskLevel: "HIGH",
        lastAssessedOn: "2026-08-01",
        lastRuleTrace: "방호덮개 없음 → '상'",
        sameAxisAsIncident: true,
      },
    ],
    unfinishedActions: [
      { actionId: 3, content: "방호덮개 설치", dueDate: "2026-09-01", status: "OVERDUE", overdueDays: 19, guideRef: null },
    ],
    priorWorkPlans: [],
    priorIncidents: [],
    knownSlots: [],
  },
  followUp: {
    assessmentId: 100,
    kindLabel: "수시평가",
    legalBasis: "산업안전보건법 시행규칙 제37조",
    regraded: [
      { hazardId: 7, accidentType: "CAUGHT", missingControl: "방호덮개 미설치", before: "MEDIUM", after: "HIGH", ruleTrace: "사고 발생 → 빈도 3", changed: true },
    ],
    newHazardId: null,
  },
  draft: {
    cause: "원인 설명",
    prevention: "재발 방지 설명",
    aiGenerated: true,
    disclaimer: "작성 보조 · 정보 제공. 최종 확정은 사람이 합니다.",
  },
};

function renderResult(r: IncidentRegisterResponse) {
  return render(
    <MemoryRouter>
      <IncidentResult r={r} />
    </MemoryRouter>,
  );
}

describe("IncidentResult", () => {
  it("예고된 사고면 히어로 제목과 사실로 조립한 증거 문장을 보인다", () => {
    renderResult(fixture);
    expect(screen.getByRole("heading", { name: "이 사고는 예고되어 있었습니다" })).toBeInTheDocument();
    expect(screen.getByText(/2026-08-01 위험성평가에서 이 설비의 협착 위험을 '상'으로 판정했고/)).toBeInTheDocument();
    expect(screen.getByText(/이미 19일 지나 있었습니다/)).toBeInTheDocument();
    // 사고 전 기록: 등급 표식 옆 룰 근거
    expect(screen.getByText("방호덮개 없음 → '상'")).toBeInTheDocument();
    expect(screen.getByText("사고 당시 기한 19일 경과")).toBeInTheDocument();
  });

  it("예고 증거가 없으면 무채색 제목과 백엔드 headline을 쓴다", () => {
    renderResult({
      ...fixture,
      recall: { ...fixture.recall, predicted: false, priorHazards: [], unfinishedActions: [], headline: "기록 없음 · 새 위험요인" },
    });
    expect(screen.getByRole("heading", { name: "사전 기록 소환 결과" })).toBeInTheDocument();
    // 가운뎃점은 쉼표로 바뀐다
    expect(screen.getByText("기록 없음, 새 위험요인")).toBeInTheDocument();
  });

  it("연쇄 4단계를 order 순서로 그리고, 단계마다 큰 값을 보인다", () => {
    renderResult({
      ...fixture,
      cascade: [step(3, "REPORT"), step(1, "RECALL"), step(4, "WORK_PLAN"), step(2, "FOLLOW_UP")],
      affectedWorkPlans: [workPlan(1)],
    });
    const chain = screen.getByRole("heading", { name: "등록 한 번으로 이어진 일" }).closest("section")!;
    const labels = within(chain).getAllByRole("button").map((b) => b.getAttribute("aria-label"));
    expect(labels[0]).toMatch(/^1단계 설비 이력 소환: 19일 경과/);
    expect(labels[1]).toMatch(/^2단계 수시평가 자동 생성: #100/);
    expect(labels[2]).toMatch(/^3단계 산업재해조사표 기한: D-20/);
    expect(labels[3]).toMatch(/^4단계 작업계획서 경고 부착: 1건/);
  });

  it("수시평가 상세는 등급 표식 옆에 룰 근거를 같이 둔다", () => {
    renderResult(fixture);
    expect(screen.getByText("종전 '중'에서 '상'으로 변경")).toBeInTheDocument();
    expect(screen.getByText("사고 발생 → 빈도 3")).toBeInTheDocument();
  });

  it("조사표 서식과 설비 타임라인 링크", () => {
    renderResult(fixture);
    const forms = screen.getAllByRole("link", { name: "산업재해조사표 서식" });
    expect(forms[0]).toHaveAttribute("href", "/form/incident/1");
    expect(forms[0]).toHaveAttribute("target", "_blank");
    expect(screen.getByRole("link", { name: "설비 타임라인 보기" })).toHaveAttribute("href", "/equipment/10");
  });

  it("작업계획서 경고: 0건이면 안내 문장, 있으면 경고 문구를 한 번만 보인다", () => {
    const { unmount } = renderResult({ ...fixture, affectedWorkPlans: [] });
    expect(screen.getByText("같은 설비에 진행 중인 작업계획서가 없습니다.")).toBeInTheDocument();
    unmount();
    renderResult({ ...fixture, affectedWorkPlans: [workPlan(1), { ...workPlan(2), workName: "도장" }] });
    expect(screen.getByText("조명 교체")).toBeInTheDocument();
    expect(screen.getAllByText("경고 문구")).toHaveLength(1);
  });

  it("조사표 초안은 접혀 있다가 펼치면 인용 칩과 근거 카드를 그린다(R42)", () => {
    renderResult({
      ...fixture,
      similarCases: [ev(1)],
      evidence: [ev(1), ev(2, "GUIDE")],
      draft: { ...fixture.draft, prevention: "덮개 설치 [#2]" },
    });
    expect(screen.queryByRole("button", { name: "근거 #2" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "문안 보기" }));
    expect(screen.getByText(/동종 유사 사고/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "근거 #2" })).toBeInTheDocument();
    expect(document.getElementById("evidence-incident-2")).not.toBeNull();
    // 면책 문구의 가운뎃점도 쉼표로 바뀐다
    expect(screen.getByText("작성 보조, 정보 제공. 최종 확정은 사람이 합니다.")).toBeInTheDocument();
  });
});
