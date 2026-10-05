// actionRow.ts: 개선대책 목록의 순수 함수(탭, 기한 표기, 부제)
import dayjs from "dayjs";
import timezone from "dayjs/plugin/timezone";
import utc from "dayjs/plugin/utc";

import type { ActionCounts, ActionListFilter, ActionListItem, ActionStatus } from "@/types/action";
import { ACCIDENT_LABEL, RISK_LABEL } from "@/types/domain";
import { formatDate, SITE_TZ } from "@/utils/datetime";

dayjs.extend(utc);
dayjs.extend(timezone);

/** 기한 임박으로 보는 남은 일수 */
export const SOON_DAYS = 7;

export const FILTERS: ActionListFilter[] = ["OPEN", "OVERDUE", "DONE", "ALL"];

const FILTER_LABEL: Record<ActionListFilter, string> = {
  OPEN: "미이행",
  OVERDUE: "기한 경과",
  DONE: "완료",
  ALL: "전체",
};

export const ACTION_STATUS_LABEL: Record<ActionStatus, string> = {
  PENDING: "미이행",
  OVERDUE: "기한 경과",
  DONE: "완료",
};

export function filterName(filter: ActionListFilter): string {
  return FILTER_LABEL[filter];
}

/** 탭 건수. 전체는 건수를 붙이지 않는다(null) */
export function filterCount(filter: ActionListFilter, counts: ActionCounts | null): number | null {
  if (filter === "ALL" || !counts) return null;
  return filter === "OPEN" ? counts.open : filter === "OVERDUE" ? counts.overdue : counts.done;
}

/** 탭 라벨 전체 문자열(예: 미이행 4). 스크린리더 이름에 쓴다 */
export function filterLabel(filter: ActionListFilter, counts: ActionCounts | null): string {
  const n = filterCount(filter, counts);
  return n === null ? FILTER_LABEL[filter] : `${FILTER_LABEL[filter]} ${n}`;
}

/** 빈 상태 문구 */
export function emptyMessage(filter: ActionListFilter, searching: boolean): string {
  if (searching) return "검색 결과 없음";
  if (filter === "ALL") return "개선대책 없음";
  return `${FILTER_LABEL[filter]} 개선대책 없음`;
}

/** URL ?status= 값. 모르는 값이면 기본(미이행) */
export function parseFilter(raw: string | null): ActionListFilter {
  const v = raw?.toUpperCase();
  return FILTERS.includes(v as ActionListFilter) ? (v as ActionListFilter) : "OPEN";
}

/** KST 오늘(YYYY-MM-DD) */
export function todayKst(): string {
  return dayjs().tz(SITE_TZ).format("YYYY-MM-DD");
}

export type DueTone = "overdue" | "soon" | "normal" | "done" | "none";

export interface DueInfo {
  /** 첫 줄 날짜. 완료면 완료일, 아니면 기한 */
  date: string;
  /** 둘째 줄 표기(N일 경과, D-N, 완료). 없으면 null */
  hint: string | null;
  tone: DueTone;
}

/** 기한 칸. 완료는 완료일, 기한 경과는 N일 경과, 7일 이내는 D-N */
export function dueInfo(row: ActionListItem, today: string): DueInfo {
  if (row.status === "DONE") {
    return { date: formatDate(row.completedAt), hint: "완료일", tone: "done" };
  }
  if (row.status === "OVERDUE") {
    return {
      date: formatDate(row.dueDate),
      hint: row.overdueDays != null ? `${row.overdueDays}일 경과` : "기한 경과",
      tone: "overdue",
    };
  }
  if (!row.dueDate) return { date: "-", hint: null, tone: "none" };
  const left = dayjs(row.dueDate).diff(dayjs(today), "day");
  if (left >= 0 && left <= SOON_DAYS) {
    return { date: row.dueDate, hint: left === 0 ? "오늘" : `D-${left}`, tone: "soon" };
  }
  return { date: row.dueDate, hint: null, tone: "normal" };
}

/** 개선대책 둘째 줄: 발생형태, 빠진 안전조치 */
export function hazardLine(row: ActionListItem): string | null {
  const parts = [row.accidentType ? ACCIDENT_LABEL[row.accidentType] : null, row.missingControl?.trim() || null];
  const text = parts.filter(Boolean).join(", ");
  return text || null;
}

/** 이행 확인 화면 머리의 기한 표기: "기한 2026-09-07, 30일 경과" */
export function dueLabel(row: ActionListItem, today: string): string | null {
  if (!row.dueDate) return null;
  const d = dueInfo(row, today);
  return d.hint && d.tone !== "done" ? `기한 ${row.dueDate}, ${d.hint}` : `기한 ${row.dueDate}`;
}

/** 확인된 대책의 둘째 줄: "개선 후 하, 확인 안전관리자 홍길동". 확인 기록이 없는 옛 완료는 null */
export function verifiedLine(row: ActionListItem): string | null {
  if (row.status !== "DONE" || !row.verifiedBy) return null;
  const residual = row.residualLevel ? `개선 후 ${RISK_LABEL[row.residualLevel]}, ` : "";
  return `${residual}확인 ${row.verifiedBy}`;
}
