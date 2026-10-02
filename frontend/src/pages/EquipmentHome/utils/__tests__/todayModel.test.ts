import { describe, expect, it } from "vitest";

import { buildKpis, buildTodayRows, filterRows, sortCards } from "@/pages/EquipmentHome/utils/todayModel";
import type { EquipmentCard, TodayItem } from "@/types/timeline";

const item = (o: Partial<TodayItem>): TodayItem => ({
  kind: "OVERDUE_ACTION", emphasis: "CRITICAL", title: "조치", detail: "", equipmentId: 1, equipmentName: "사다리",
  dueDate: "2026-10-03", daysRemaining: 1, linkType: "EQUIPMENT", refId: 1, ...o,
});

const card = (o: Partial<EquipmentCard>): EquipmentCard => ({
  id: 1, name: "설비", locationTag: null, processName: null, currentRiskLevel: null, currentRiskAxis: null,
  lastAssessedOn: null, unfinishedActionCount: 0, overdueActionCount: 0, upcomingWorkPlanCount: 0, incidentCount: 0,
  lastEventOn: null, emphasis: "NORMAL", headline: "", ...o,
});

describe("buildTodayRows", () => {
  it("같은 계획서의 승인 대기 행은 위험 작업 행에 합치고 '검토' 버튼을 단다", () => {
    const rows = buildTodayRows([
      item({ kind: "RISKY_WORK_PLAN", linkType: "WORK_PLAN", refId: 30, title: "천장 페인트 작업" }),
      item({ kind: "PENDING_APPROVAL", emphasis: "WARNING", linkType: "WORK_PLAN", refId: 30, title: "천장 페인트 작업" }),
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0].awaitingApproval).toBe(true);
    expect(rows[0].action).toEqual({ label: "검토", href: "/work-plan?planId=30", external: false });
  });

  it("같은 설비, 같은 작업일의 계획서 여러 건은 '외 n건'으로 접는다", () => {
    const rows = buildTodayRows([
      item({ kind: "RISKY_WORK_PLAN", linkType: "WORK_PLAN", refId: 30, title: "A 작업" }),
      item({ kind: "RISKY_WORK_PLAN", linkType: "WORK_PLAN", refId: 31, title: "B 작업" }),
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0].title).toBe("A 작업 외 1건");
    expect(rows[0].count).toBe(2);
  });

  it("옛 응답 제목의 종류 접두와 대시를 걷어낸다", () => {
    const [row] = buildTodayRows([item({ title: "기한 초과 조치 — 앵커 설치 (42일 경과)" })]);
    expect(row.title).toBe("앵커 설치");
  });
});

describe("buildKpis / filterRows", () => {
  const items = [
    item({ kind: "OVERDUE_ACTION", daysRemaining: -42 }),
    item({ kind: "OVERDUE_ACTION", daysRemaining: -4, refId: 2 }),
    item({ kind: "DUE_ACTION", emphasis: "WARNING", daysRemaining: 3, refId: 4 }),
    item({ kind: "PENDING_APPROVAL", emphasis: "WARNING", linkType: "WORK_PLAN", refId: 9 }),
    item({ kind: "REPORT_DUE", emphasis: "WARNING", linkType: "INCIDENT", daysRemaining: 31, refId: 3 }),
  ];

  it("숫자는 서버 항목에서만 센다", () => {
    const kpis = buildKpis(items, [card({ currentRiskLevel: "HIGH", name: "사다리" })]);
    const byKey = Object.fromEntries(kpis.map((k) => [k.key, k]));
    expect(byKey.overdue.value).toBe(2);
    expect(byKey.overdue.note).toBe("최장 42일");
    expect(byKey.week.value).toBe(1);
    expect(byKey.approval.value).toBe(1);
    expect(byKey.report.value).toBe(1);
    expect(byKey.report.note).toBe("기한 10-03");
    expect(byKey.highRisk.value).toBe(1);
    expect(kpis.map((k) => k.label)).toEqual(["기한 경과 조치", "7일 내 마감", "승인 대기", "조사표 미제출", "고위험 설비"]);
  });

  it("KPI를 누르면 그 종류만 남긴다", () => {
    const rows = buildTodayRows(items);
    expect(filterRows(rows, "overdue")).toHaveLength(2);
    expect(filterRows(rows, "approval")).toHaveLength(1);
    expect(filterRows(rows, null)).toHaveLength(rows.length);
  });
});

describe("sortCards", () => {
  it("등급 상이 먼저, 그 안에서는 기한 경과가 먼저다", () => {
    const sorted = sortCards([
      card({ id: 1, name: "중, 경과", currentRiskLevel: "MEDIUM", overdueActionCount: 1, emphasis: "CRITICAL" }),
      card({ id: 2, name: "상", currentRiskLevel: "HIGH", emphasis: "WARNING" }),
      card({ id: 3, name: "상, 경과", currentRiskLevel: "HIGH", overdueActionCount: 1, emphasis: "CRITICAL" }),
      card({ id: 4, name: "미평가" }),
    ]);
    expect(sorted.map((c) => c.name)).toEqual(["상, 경과", "상", "중, 경과", "미평가"]);
  });
});
