// todayModel.ts — "오늘 할 일" 응답을 홈 화면 모양으로 접는다(순수 함수).
// 서버 정렬(긴급, 주의, 기한 순)은 그대로 두고, 같은 계획서가 두 규칙에 걸린 경우만 한 줄로 합친다.
import { formUrl } from "@/api/formUrl";
import { monthDay, plainText } from "@/utils/plainText";
import { ACCIDENT_LABEL } from "@/types/domain";
import type { EquipmentCard, TodayItem, TodayKind } from "@/types/timeline";
import type { Tone } from "@/utils/statusColors";

export const KIND_LABEL: Record<TodayKind, string> = {
  WORK_HOLD: "작업 보류",
  OVERDUE_ACTION: "기한 경과 조치",
  DUE_ACTION: "조치 기한 임박",
  RISKY_WORK_PLAN: "위험 작업 예정",
  PENDING_APPROVAL: "승인 대기",
  REPORT_DUE: "산업재해조사표",
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
  /** 오른쪽 기한 칸. 경과는 "n일 경과", 법정 기한은 날짜, 나머지는 "D-n" */
  due: { text: string; tone: Tone } | null;
  action: TodayAction | null;
  /** 제목 클릭 시 설비 상세 */
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

/** 남은 일수를 목록 표기로. 지난 기한은 "n일 경과", 오늘은 "오늘", 남은 기한은 "D-n" */
export function dueText(days: number): string {
  if (days < 0) return `${Math.abs(days)}일 경과`;
  if (days === 0) return "오늘";
  return `D-${days}`;
}

function dueOf(item: TodayItem): TodayRowModel["due"] {
  const days = item.daysRemaining;
  // 작업 보류는 기한이 아니라 금지다. 날짜 칸에는 보류된 작업의 작업일을 둔다
  if (item.kind === "WORK_HOLD") return item.dueDate ? { text: `작업일 ${monthDay(item.dueDate)}`, tone: "neutral" } : null;
  if (days === null) return null;
  const tone: Tone = days < 0 ? "high" : days <= 3 ? "pending" : "neutral";
  // 법정 1개월 기한은 날짜로 말한다(숫자 카운트다운을 강조하지 않는다)
  if (item.kind === "REPORT_DUE" && days >= 0 && item.dueDate) return { text: `기한 ${monthDay(item.dueDate)}`, tone };
  return { text: dueText(days), tone };
}

function actionOf(item: TodayItem): TodayAction | null {
  const eq = item.equipmentId;
  switch (item.kind) {
    case "WORK_HOLD":
      // 사고가 만든 수시평가를 확정해야 보류가 풀린다. 수시평가 id가 없으면 사고 보고 화면으로
      return { label: "수시평가", href: item.assessmentId !== null && item.assessmentId !== undefined ? `/assessment/${item.assessmentId}` : "/incident", external: false };
    case "OVERDUE_ACTION":
    case "DUE_ACTION":
      return eq !== null && item.refId !== null
        ? { label: "조치 확인", href: `/equipment/${eq}?focus=action-${item.refId}`, external: false }
        : null;
    case "RISKY_WORK_PLAN":
    case "PENDING_APPROVAL":
      return item.refId !== null ? { label: "검토", href: `/work-plan?planId=${item.refId}`, external: false } : null;
    case "REPORT_DUE":
      return item.refId !== null ? { label: "조사표 작성", href: `/incident?incidentId=${item.refId}`, external: false } : null;
    case "PATROL_DUE":
      return { label: "순회점검", href: "/vision", external: false };
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
      due: dueOf(item),
      action: actionOf(item),
      equipmentHref: item.equipmentId !== null ? `/equipment/${item.equipmentId}` : null,
    };
  });
}

/** KPI 필터. 오늘 할 일 목록을 이 종류로 좁힌다 */
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
  /** 숫자 아래 한 줄. 꼭 필요한 사실만(최장 경과일, 가장 가까운 법정 기한) */
  note: string | null;
  tone: "high" | "pending" | "neutral";
}

/** "7일 내 마감": 기한 임박 조치와 위험 작업 예정 중 오늘부터 7일 안. KPI 숫자와 목록 필터가 이 한 규칙을 쓴다 */
function inWeek(row: TodayRowModel): boolean {
  const days = row.item.daysRemaining;
  return KPI_KINDS.week.includes(row.item.kind) && days !== null && days >= 0 && days <= 7;
}

/** KPI 하나가 고르는 행. 숫자와 필터 결과가 늘 같도록 KPI 값도 이 함수로 센다 */
function matches(row: TodayRowModel, key: Exclude<KpiKey, "highRisk">): boolean {
  if (key === "approval") return row.item.kind === "PENDING_APPROVAL" || row.awaitingApproval;
  if (key === "week") return inWeek(row);
  return KPI_KINDS[key].includes(row.item.kind);
}

/** 서버 데이터에서만 계산한다. 숫자를 화면이 지어내지 않는다. 숫자는 목록 행 기준(필터 결과와 같은 수) */
export function buildKpis(rows: TodayRowModel[], cards: EquipmentCard[]): KpiModel[] {
  const overdue = rows.filter((r) => matches(r, "overdue"));
  const report = rows.filter((r) => matches(r, "report"));
  const high = cards.filter((c) => c.currentRiskLevel === "HIGH");
  const worstOverdue = overdue.length ? Math.max(...overdue.map((r) => -(r.item.daysRemaining ?? 0))) : 0;
  const nextReport = report
    .map((r) => r.item)
    .filter((i) => i.dueDate !== null)
    .sort((a, b) => (a.dueDate! < b.dueDate! ? -1 : 1))[0];
  return [
    { key: "overdue", label: "기한 경과 조치", value: overdue.length, tone: "high",
      note: overdue.length ? `최장 ${worstOverdue}일` : null },
    { key: "week", label: "7일 내 마감", value: rows.filter((r) => matches(r, "week")).length, tone: "pending", note: null },
    { key: "approval", label: "승인 대기", value: rows.filter((r) => matches(r, "approval")).length, tone: "pending", note: null },
    { key: "report", label: "조사표 미제출", value: report.length, tone: "pending",
      note: nextReport?.dueDate ? `기한 ${monthDay(nextReport.dueDate)}` : null },
    { key: "highRisk", label: "고위험 설비", value: high.length, tone: "high", note: null },
  ];
}

/** KPI 필터. 고위험 설비는 오늘 할 일이 아니라 설비 목록을 좁히므로 여기서는 전체를 돌려준다 */
export function filterRows(rows: TodayRowModel[], key: KpiKey | null): TodayRowModel[] {
  if (!key || key === "highRisk") return rows;
  return rows.filter((r) => matches(r, key));
}

/** 고위험 설비 필터: 현재 등급 상인 설비만 */
export function filterHighRisk(cards: EquipmentCard[], on: boolean): EquipmentCard[] {
  return on ? cards.filter((c) => c.currentRiskLevel === "HIGH") : cards;
}

const RISK_RANK = { HIGH: 0, MEDIUM: 1, LOW: 2 } as const;
const EMPHASIS_RANK = { CRITICAL: 0, WARNING: 1, NORMAL: 2 } as const;

/** 설비 카드 정렬(위험도순): 등급 상 먼저, 그다음 기한 경과 조치, 서버 강조도, 등급, 미이행 수 */
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

/** 설비 이름순 정렬(정렬 셀렉트의 두 번째 선택지) */
export function sortCardsByName(cards: EquipmentCard[]): EquipmentCard[] {
  return [...cards].sort((a, b) => a.name.localeCompare(b.name, "ko"));
}

export interface CardChip {
  text: string;
  tone: Tone;
}

/**
 * 설비 상태 칩 하나. 서버 headline과 같은 규칙(기한 경과, 사고, 최초 평가 필요, 미이행, 아차사고 순)을
 * 카드 숫자에서 다시 읽는다. 톤을 정하려면 종류가 필요해서다.
 */
export function cardChip(c: Pick<EquipmentCard, "overdueActionCount" | "incidentCount" | "unfinishedActionCount" | "currentRiskLevel"> & { nearMissCount?: number }): CardChip | null {
  if (c.overdueActionCount > 0) return { text: `기한 경과 ${c.overdueActionCount}`, tone: "high" };
  if (c.incidentCount > 0) return { text: `사고 ${c.incidentCount}`, tone: "high" };
  if (!c.currentRiskLevel) return { text: "최초 평가 필요", tone: "pending" };
  if (c.unfinishedActionCount > 0) return { text: `미이행 ${c.unfinishedActionCount}`, tone: "pending" };
  if ((c.nearMissCount ?? 0) > 0) return { text: `아차사고 ${c.nearMissCount}`, tone: "pending" };
  return null;
}

/** 우상단 한 줄: "최근 평가 떨어짐 10-04". 평가가 없으면 null */
export function assessedLabel(card: Pick<EquipmentCard, "currentRiskAxis" | "lastAssessedOn">): string | null {
  if (!card.lastAssessedOn && !card.currentRiskAxis) return null;
  const parts = ["최근 평가", card.currentRiskAxis ? ACCIDENT_LABEL[card.currentRiskAxis] : null,
    card.lastAssessedOn ? monthDay(card.lastAssessedOn) : null];
  return parts.filter(Boolean).join(" ");
}
