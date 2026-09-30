import { fireEvent, render, screen } from "@testing-library/react";
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
    priorHazards: [],
    unfinishedActions: [],
    priorWorkPlans: [],
    priorIncidents: [],
    knownSlots: [],
  },
  followUp: {
    assessmentId: 100,
    kindLabel: "수시평가",
    legalBasis: "산업안전보건법 시행규칙 제37조",
    regraded: [],
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
  it("similarCases·evidence가 없으면 유사 사례 섹션도, 인용 칩도 없다", () => {
    renderResult(fixture);
    expect(screen.queryByText(/동종 유사 사고/)).toBeNull();
    expect(screen.queryByRole("button", { name: /근거 #/ })).toBeNull();
  });

  it("동종 유사 사고 카드와 조사표 인용 칩", () => {
    renderResult({ ...fixture, similarCases: [ev(1)], evidence: [ev(1), ev(2)], draft: { ...fixture.draft, prevention: "덮개 설치 [#2]" } });
    expect(screen.getByText(/동종 유사 사고/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "근거 #2" })).toBeInTheDocument();
  });

  it("R42: LAW가 아닌 근거도 카드로 그려지고 인용 칩이 known으로 표시된다", () => {
    // #2는 유사 사례 그리드에 없는, kind=GUIDE(LAW 아님) 근거 — 예전 필터(kind==='LAW')였다면
    // 카드 없이 칩만 떠서 클릭해도 스크롤할 대상이 없었다.
    renderResult({
      ...fixture,
      evidence: [ev(2, "GUIDE")],
      draft: { ...fixture.draft, prevention: "덮개 설치 [#2]" },
    });
    expect(screen.getByRole("button", { name: "근거 #2" })).toBeInTheDocument();
    // 하단 근거 그리드는 접힌 채로 시작한다(collapsedByDefault) — 펼쳐야 카드가 DOM에 그려진다.
    // 예전 필터(kind==='LAW')였다면 GUIDE 종류인 #2는 펼쳐도 카드가 없어 칩이 가리킬 곳이 없었다.
    fireEvent.click(screen.getByRole("button", { name: /^근거 1건/ }));
    expect(document.getElementById("evidence-incident-2")).not.toBeNull();
  });

  it("cascade가 없으면 스텝 목록을 렌더하지 않는다", () => {
    renderResult(fixture);
    expect(screen.queryByRole("list", { name: "사고 연쇄" })).toBeNull();
  });

  it("cascade가 있으면 스텝 목록을 최상단에 렌더한다", () => {
    renderResult({
      ...fixture,
      cascade: [step(1, "RECALL"), step(2, "FOLLOW_UP"), step(3, "REPORT"), step(4, "WORK_PLAN")],
    });
    expect(screen.getByText("스텝 1")).toBeInTheDocument();
    expect(screen.getByText("스텝 4")).toBeInTheDocument();
  });

  it("affectedWorkPlans가 없으면(0건) 표를 렌더하지 않는다", () => {
    renderResult({ ...fixture, affectedWorkPlans: [] });
    expect(screen.queryByText("조명 교체")).toBeNull();
  });

  it("affectedWorkPlans가 있으면 표를 렌더한다", () => {
    renderResult({ ...fixture, affectedWorkPlans: [workPlan(1)] });
    expect(screen.getByText("조명 교체")).toBeInTheDocument();
    expect(screen.getByText("경고 문구")).toBeInTheDocument();
  });
});
