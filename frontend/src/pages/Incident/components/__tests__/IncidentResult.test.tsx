import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

import IncidentResult from "@/pages/Incident/components/IncidentResult";
import type { Evidence, EvidenceKind } from "@/types/evidence";
import type { AffectedWorkPlan, IncidentRegisterResponse } from "@/types/incident";

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

function workPlan(id: number, workName = "차양부 천장 도장"): AffectedWorkPlan {
  return { workPlanId: id, workName, workDate: "2026-10-03", status: "HOLD", warning: "작업 보류: 수시평가 완료 전 작업 재개 금지" };
}

const fixture: IncidentRegisterResponse = {
  incident: {
    id: 1,
    siteId: 1,
    equipmentId: 10,
    equipmentName: "이동식 사다리 A",
    workPlanId: null,
    occurredAt: "2026-10-02T10:20:00+09:00",
    victimName: null,
    severity: "LOST_TIME",
    leaveDays: 5,
    incidentType: "FALL",
    incidentTypeLabel: "떨어짐",
    accidentType: "FALL",
    description: "차양부 천장 도장 중 떨어짐",
    injuryType: "골절",
    injuryPart: "왼쪽 발목",
    followUpAssessmentId: 100,
  },
  reportDuty: {
    status: "REQUIRED",
    statusLabel: "제출 필요",
    dueDate: "2026-11-02",
    daysRemaining: 31,
    basis: "휴업예상일수 5일",
    seriousAccidentPossible: false,
    submittedOn: null,
  },
  recall: {
    equipmentId: 10,
    equipmentName: "이동식 사다리 A",
    locationTag: "공장동 후면 차양부",
    headline: "사고 전 평가 떨어짐 상",
    predicted: true,
    warnedAt: null,
    priorHazards: [
      {
        hazardId: 7,
        accidentType: "FALL",
        missingControl: "작업발판 미확보",
        description: null,
        lastRiskLevel: "HIGH",
        lastAssessedOn: "2026-09-02",
        lastRuleTrace: "발판 높이 3.2m, 최상부 디딤대 사용 → '상'",
        sameAxisAsIncident: true,
      },
    ],
    unfinishedActions: [
      {
        actionId: 3,
        content: "차양부 천장 작업 시 이동식 비계(안전난간) 사용",
        dueDate: "2026-09-02",
        status: "OVERDUE",
        overdueDays: 30,
        guideRef: null,
        owner: "생산반장 김철수",
      },
    ],
    priorWorkPlans: [],
    priorIncidents: [],
    knownSlots: [],
  },
  followUp: {
    assessmentId: 100,
    kindLabel: "수시",
    legalBasis: "시행규칙 제37조제2항제3호",
    regraded: [
      {
        hazardId: 7,
        accidentType: "FALL",
        missingControl: "작업발판 미확보",
        before: "HIGH",
        after: "HIGH",
        ruleTrace: "떨어짐 사고 발생, 휴업예상 5일, 사고 전 상",
        changed: false,
      },
    ],
    newHazardId: null,
    status: "DRAFT",
    confirmedOn: null,
  },
  draft: {
    cause: "차양부 천장 도장 중 이동식 사다리 최상부 바로 아래 디딤대에서 떨어짐.",
    prevention: "1. 이동식 비계 사용 (담당 생산반장 김철수, 기한 작업 재개 전) [#2]",
    aiGenerated: true,
    disclaimer: "작성 보조",
  },
  similarCases: [],
  evidence: [],
  cascade: [],
  affectedWorkPlans: [],
};

function renderResult(r: IncidentRegisterResponse) {
  return render(
    <MemoryRouter>
      <IncidentResult r={r} />
    </MemoryRouter>,
  );
}

describe("IncidentResult", () => {
  it("제목은 사실형(발생형태 사고, 설비, YYYY-MM-DD HH:mm)이고 판단 문장을 쓰지 않는다", () => {
    renderResult(fixture);
    expect(screen.getByRole("heading", { level: 2, name: "떨어짐 사고, 이동식 사다리 A, 2026-10-02 10:20" })).toBeInTheDocument();
    expect(screen.queryByText(/예고/)).toBeNull();
    expect(screen.getByText("공장동 후면 차양부, 휴업예상 5일, 골절, 왼쪽 발목")).toBeInTheDocument();
  });

  it("서식 버튼 3개: 산업재해조사표(새 탭), 재발방지 검토서(새 탭), 설비 이력", () => {
    renderResult(fixture);
    const report = screen.getAllByRole("link", { name: "산업재해조사표" })[0];
    expect(report).toHaveAttribute("href", "/form/incident/1");
    expect(report).toHaveAttribute("target", "_blank");
    expect(screen.getByRole("link", { name: "재발방지 검토서" })).toHaveAttribute("href", "/form/incident/1/review");
    expect(screen.getByRole("link", { name: "설비 이력" })).toHaveAttribute("href", "/equipment/10");
  });

  it("사고 전 기록: 등급 표식, 위험요인, 감소대책 담당과 기한. 경과일은 막대 위에 한 번만", () => {
    renderResult(fixture);
    const prior = screen.getByRole("region", { name: "사고 전 기록" });
    expect(within(prior).getByLabelText("위험성 상")).toBeInTheDocument();
    expect(within(prior).getByText("사고 전 평가 2026-09-02")).toBeInTheDocument();
    expect(within(prior).getByText("작업발판 미확보")).toBeInTheDocument();
    expect(within(prior).getByText("발판 높이 3.2m, 최상부 디딤대 사용, 상")).toBeInTheDocument();
    expect(within(prior).getByText("생산반장 김철수")).toBeInTheDocument();
    expect(within(prior).getByText("미이행")).toBeInTheDocument();
    expect(within(prior).getAllByText(/30일 경과/)).toHaveLength(1);
    expect(within(prior).getByLabelText("감소대책 기한 2026-09-02부터 사고까지 30일")).toBeInTheDocument();
    expect(within(prior).getByText("2026-10-02")).toBeInTheDocument();
  });

  it("같은 발생형태의 사전 기록이 없으면 한 줄로 말한다", () => {
    renderResult({ ...fixture, recall: { ...fixture.recall, priorHazards: [], unfinishedActions: [] } });
    expect(screen.getByText("같은 발생형태의 위험요인 기록 없음")).toBeInTheDocument();
  });

  it("중대재해 안내는 사망일 때만 배너로 뜬다", () => {
    const { unmount } = renderResult(fixture);
    expect(screen.queryByText(/중대재해/)).toBeNull();
    unmount();
    renderResult({ ...fixture, reportDuty: { ...fixture.reportDuty, seriousAccidentPossible: true } });
    expect(screen.getByText("중대재해 해당 가능, 지체 없이 관할 지방고용노동관서 보고")).toBeInTheDocument();
  });

  it("후속 조치 3장: 수시평가(작성 중, 화면으로 이동), 조사표(기한 날짜, D-n 없음), 작업 보류", () => {
    renderResult({ ...fixture, affectedWorkPlans: [workPlan(5)] });
    const follow = screen.getByRole("article", { name: "수시평가" });
    expect(within(follow).getByText("작성 중")).toBeInTheDocument();
    expect(within(follow).getByRole("link", { name: "수시평가" })).toHaveAttribute("href", "/assessment/100");

    const report = screen.getByRole("article", { name: "산업재해조사표" });
    expect(within(report).getByText("기한 2026-11-02")).toBeInTheDocument();
    expect(within(report).queryByText(/D-/)).toBeNull();
    expect(within(report).getByRole("link", { name: "조사표 작성" })).toHaveAttribute("href", "/form/incident/1");

    const hold = screen.getByRole("article", { name: "작업 보류" });
    expect(within(hold).getByText("1건")).toBeInTheDocument();
    expect(within(hold).getByText("차양부 천장 도장")).toBeInTheDocument();
    expect(within(hold).getAllByText("작업 보류").length).toBeGreaterThanOrEqual(2);
    expect(within(hold).getByRole("link", { name: "작업 전 점검" })).toHaveAttribute("href", "/work-plan?planId=5");
  });

  it("수시평가를 확정했으면 카드 제목이 확정일이고, 보류는 재승인 대기로 풀려 보인다", () => {
    renderResult({
      ...fixture,
      followUp: { ...fixture.followUp, status: "CONFIRMED", confirmedOn: "2026-10-07" },
      affectedWorkPlans: [{ ...workPlan(5), status: "SUBMITTED" }],
    });
    expect(within(screen.getByRole("article", { name: "수시평가" })).getByText("확정 2026-10-07")).toBeInTheDocument();
    const hold = screen.getByRole("article", { name: "작업 보류" });
    expect(within(hold).getByText("해제 1건")).toBeInTheDocument();
    expect(within(hold).getByText("재승인 대기")).toBeInTheDocument();
  });

  it("보류 대상이 없으면 0건이고 버튼이 없다", () => {
    renderResult(fixture);
    const hold = screen.getByRole("article", { name: "작업 보류" });
    expect(within(hold).getByText("0건")).toBeInTheDocument();
    expect(within(hold).queryByRole("link")).toBeNull();
  });

  it("제출 완료 사고는 조사표 작성 버튼 없이 제출일을 말한다", () => {
    renderResult({ ...fixture, reportDuty: { ...fixture.reportDuty, status: "SUBMITTED", statusLabel: "제출 완료", submittedOn: "2026-10-12" } });
    const report = screen.getByRole("article", { name: "산업재해조사표" });
    expect(within(report).getByText("제출 완료 2026-10-12")).toBeInTheDocument();
    expect(within(report).queryByRole("link", { name: "조사표 작성" })).toBeNull();
    expect(within(report).queryByRole("button", { name: "제출 완료" })).toBeNull();
  });

  it("제출 필요 사고는 제출 완료 처리 버튼이 있다", () => {
    const onMark = vi.fn();
    render(
      <MemoryRouter>
        <IncidentResult r={fixture} onMarkSubmitted={onMark} />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole("button", { name: "제출 완료" }));
    expect(onMark).toHaveBeenCalled();
  });

  it("아차사고는 제목이 아차사고이고, 조사표 버튼과 초안이 없으며 기록 서식은 '사고 기록'이다", () => {
    renderResult({
      ...fixture,
      incident: { ...fixture.incident, severity: "NEAR_MISS", incidentType: "STRUCK", incidentTypeLabel: "부딪힘", leaveDays: null, injuryType: null, injuryPart: null },
      reportDuty: { ...fixture.reportDuty, status: "NOT_REQUIRED", statusLabel: "제출 의무 없음", dueDate: null, basis: "아차사고, 조사표 제출 대상 아님" },
    });
    expect(screen.getByRole("heading", { level: 2, name: "부딪힘 아차사고, 이동식 사다리 A, 2026-10-02 10:20" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "산업재해조사표" })).toBeNull();
    expect(screen.queryByRole("link", { name: "조사표 작성" })).toBeNull();
    expect(screen.getByRole("link", { name: "사고 기록" })).toHaveAttribute("href", "/form/incident/1/review");
    expect(screen.queryByRole("region", { name: "조사표 초안" })).toBeNull();
    expect(within(screen.getByRole("article", { name: "산업재해조사표" })).getByText("제출 대상 아님")).toBeInTheDocument();
  });

  it("조사표 초안은 접혀 있다가 펼치면 원인, 재발방지, 인용 칩을 그린다", () => {
    renderResult({ ...fixture, similarCases: [ev(1, "CASE_DISASTER")], evidence: [ev(1), ev(2, "LAW")] });
    expect(screen.queryByText("재해발생 원인")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "펼치기" }));
    expect(screen.getByText("재해발생 원인")).toBeInTheDocument();
    expect(screen.getByText("재발방지 계획")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "근거 #2" })).toHaveTextContent(/^2$/);
    expect(screen.queryByText(/AI/)).toBeNull();
    expect(screen.queryByText(/#\d/)).toBeNull();
  });
});
