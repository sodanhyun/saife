// todayModel.ts — "오늘 할 일" 응답을 홈 화면 모양으로 접는다(순수 함수).
// 서버 정렬(긴급 → 주의, 기한 순)은 그대로 두고, 같은 계획서가 두 규칙에 걸린 경우만 한 줄로 합친다.
import { formUrl } from "@/api/formUrl";
import { plainText } from "@/components/timeline/plainText";
import type { EquipmentCard, TodayItem, TodayKind } from "@/types/timeline";

export const KIND_LABEL: Record<TodayKind, string> = {
  OVERDUE_ACTION: "기한 경과 조치",
  DUE_ACTION: "조치 기한 임박",
  RISKY_WORK_PLAN: "위험 작업 예정",
  PENDING_APPROVAL: "승인 대기",
  REPORT_DUE: "조사표 제출",
  PATROL_DUE: "순회점검",
  PERIODIC_DUE: "정기평가",
};

export interface TodayAction {
  label: string;
  href: string;
  external: boolean;
}

export interface TodayRowModel {
  key: string;
  item: TodayItem;
  /** 한 줄로 접힌 원본 항목 수(같은 설비, 같은 작업일의 계획서) */
  count: number;
  /** 같은 계획서가 승인 대기이기도 하면 true(위험 작업 행 하나로 합친다) */
  awaitingApproval: boolean;
  title: string;
  detail: string;
  action: TodayAction | null;
  /** 행 클릭 시 설비 상세 */
  equipmentHref: string | null;
}

/** 옛 응답 제목("기한 초과 조치 — 내용 (42일 경과)")도 같은 모양으로 읽는다 */
function cleanTitle(item: TodayItem): string {
  let t = item.title;
  const dash = t.indexOf(" — ");
  if (dash > 0) t = t.slice(dash + 3);
  t = t.replace(/\s\((\d+일 경과|설비 [^)]*)\)$/, "");
  return plainText(t);
}

function actionOf(item: TodayItem, awaitingApproval: boolean): TodayAction | null {
  const eq = item.equipmentId;
  switch (item.kind) {
    case "OVERDUE_ACTION":
    case "DUE_ACTION":
      // 조치 이행 여부는 현장 사진으로 확인한다(UC1)
      return { label: "사진 점검", href: eq !== null ? `/vision?equipmentId=${eq}` : "/vision", external: false };
    case "RISKY_WORK_PLAN":
    case "PENDING_APPROVAL":
      return item.refId !== null
        ? { label: awaitingApproval || item.kind === "PENDING_APPROVAL" ? "승인 검토" : "계획서", href: `/work-plan?planId=${item.refId}`, external: false }
        : null;
    case "REPORT_DUE":
      return item.refId !== null ? { label: "조사표", href: formUrl.incident(item.refId), external: true } : null;
    case "PATROL_DUE":
      return { label: "사진 점검", href: "/vision", external: false };
    case "PERIODIC_DUE":
      return item.refId !== null ? { label: "평가표", href: formUrl.assessment(item.refId), external: true } : null;
    default:
      return null;
  }
}

const PLAN_KINDS: TodayKind[] = ["RISKY_WORK_PLAN", "PENDING_APPROVAL"];

export function buildTodayRows(items: TodayItem[]): TodayRowModel[] {
  const riskyPlanIds = new Set(items.filter((i) => i.kind === "RISKY_WORK_PLAN" && i.refId !== null).map((i) => i.refId));
  const pendingPlanIds = new Set(items.filter((i) => i.kind === "PENDING_APPROVAL" && i.refId !== null).map((i) => i.refId));
  const kept = items.filter((i) => !(i.kind === "PENDING_APPROVAL" && riskyPlanIds.has(i.refId)));

  // 같은 설비, 같은 작업일의 계획서 여러 건은 한 줄로 접는다(목록이 한 설비로 도배되지 않게)
  const groups = new Map<string, TodayItem[]>();
  const order: string[] = [];
  kept.forEach((item, idx) => {
    const key = PLAN_KINDS.includes(item.kind) && item.equipmentId !== null
      ? `${item.kind}-${item.equipmentId}-${item.dueDate}`
      : `${item.kind}-${item.refId ?? "none"}-${idx}`;
    if (!groups.has(key)) { groups.set(key, []); order.push(key); }
    groups.get(key)!.push(item);
  });

  return order.map((key) => {
    const group = groups.get(key)!;
    const item = group[0];
    const awaitingApproval = item.kind === "RISKY_WORK_PLAN" && group.some((g) => pendingPlanIds.has(g.refId));
    const title = cleanTitle(item);
    return {
      key,
      item,
      count: group.length,
      awaitingApproval,
      title: group.length > 1 ? `${title} 외 ${group.length - 1}건` : title,
      detail: plainText(item.detail),
      action: actionOf(item, awaitingApproval),
      equipmentHref: item.equipmentId !== null ? `/equipment/${item.equipmentId}` : null,
    };
  });
}

/** KPI 필터 — 오늘 할 일 목록을 이 종류로 좁힌다 */
export type KpiKey = "overdue" | "week" | "approval" | "report" | "highRisk";

export const KPI_KINDS: Record<Exclude<KpiKey, "highRisk">, TodayKind[]> = {
  overdue: ["OVERDUE_ACTION"],
  week: ["DUE_ACTION", "RISKY_WORK_PLAN"],
  approval: ["PENDING_APPROVAL"],
  report: ["REPORT_DUE"],
};

export interface KpiModel {
  key: KpiKey;
  label: string;
  value: number;
  note: string;
  tone: "high" | "pending" | "neutral";
}

function nearest(items: TodayItem[]): number | null {
  const days = items.map((i) => i.daysRemaining).filter((d): d is number => d !== null);
  return days.length ? Math.min(...days) : null;
}

/** 서버 데이터에서만 계산한다. 숫자를 화면이 지어내지 않는다 */
export function buildKpis(items: TodayItem[], cards: EquipmentCard[]): KpiModel[] {
  const of = (kinds: TodayKind[]) => items.filter((i) => kinds.includes(i.kind));
  const overdue = of(KPI_KINDS.overdue);
  const week = of(KPI_KINDS.week).filter((i) => i.daysRemaining !== null && i.daysRemaining <= 7);
  const approval = of(KPI_KINDS.approval);
  const report = of(KPI_KINDS.report);
  const high = cards.filter((c) => c.currentRiskLevel === "HIGH");
  const worstOverdue = overdue.length ? Math.max(...overdue.map((i) => -(i.daysRemaining ?? 0))) : 0;
  const reportNext = nearest(report);
  return [
    { key: "overdue", label: "기한 경과 조치", value: overdue.length, tone: "high",
      note: overdue.length ? `최장 ${worstOverdue}일 경과` : "없음" },
    { key: "week", label: "7일 내 기한", value: week.length, tone: "pending",
      note: week.length ? "조치와 위험 작업" : "없음" },
    { key: "approval", label: "승인 대기 계획서", value: approval.length, tone: "pending",
      note: approval.length ? "승인 전 작업 불가" : "없음" },
    { key: "report", label: "조사표 제출", value: report.length, tone: reportNext !== null && reportNext <= 3 ? "high" : "pending",
      note: reportNext !== null ? `가장 가까운 기한 D-${Math.max(reportNext, 0)}` : "없음" },
    { key: "highRisk", label: "위험성 '상' 설비", value: high.length, tone: "high",
      note: high.length ? high.map((c) => c.name).join(", ") : "없음" },
  ];
}

export function filterRows(rows: TodayRowModel[], key: KpiKey | null): TodayRowModel[] {
  if (!key || key === "highRisk") return rows;
  const kinds = KPI_KINDS[key];
  return rows.filter((r) => {
    if (key === "approval") return r.item.kind === "PENDING_APPROVAL" || r.awaitingApproval;
    if (key === "week") return kinds.includes(r.item.kind) && r.item.daysRemaining !== null && r.item.daysRemaining <= 7;
    return kinds.includes(r.item.kind);
  });
}

const RISK_RANK = { HIGH: 0, MEDIUM: 1, LOW: 2 } as const;
const EMPHASIS_RANK = { CRITICAL: 0, WARNING: 1, NORMAL: 2 } as const;

/** 설비 카드 정렬: 등급 상 먼저, 그다음 기한 경과 조치, 서버 강조도, 미이행 수 */
export function sortCards(cards: EquipmentCard[]): EquipmentCard[] {
  return [...cards].sort((a, b) => {
    const ra = a.currentRiskLevel ? RISK_RANK[a.currentRiskLevel] : 3;
    const rb = b.currentRiskLevel ? RISK_RANK[b.currentRiskLevel] : 3;
    const highA = ra === 0 ? 0 : 1;
    const highB = rb === 0 ? 0 : 1;
    if (highA !== highB) return highA - highB;
    if ((b.overdueActionCount > 0 ? 1 : 0) !== (a.overdueActionCount > 0 ? 1 : 0)) return b.overdueActionCount > 0 ? 1 : -1;
    if (EMPHASIS_RANK[a.emphasis] !== EMPHASIS_RANK[b.emphasis]) return EMPHASIS_RANK[a.emphasis] - EMPHASIS_RANK[b.emphasis];
    if (ra !== rb) return ra - rb;
    return b.unfinishedActionCount - a.unfinishedActionCount;
  });
}
