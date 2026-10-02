// dates.ts — 개선대책 기한 계산과 표기. 사업장 기준시(KST)로 센다.
import dayjs from "dayjs";

import { SITE_TZ } from "@/utils/datetime";

/** 개선대책 기한 기본값(오늘부터 며칠) */
export const DEFAULT_DUE_DAYS = 14;

/** 기한 기본값: 오늘 + 14일 (YYYY-MM-DD) */
export function defaultDueDate(): string {
  return dayjs().tz(SITE_TZ).add(DEFAULT_DUE_DAYS, "day").format("YYYY-MM-DD");
}

/** 오늘부터 기한까지 남은 일수. 음수면 지났다 */
export function daysUntil(due: string | null): number | null {
  if (!due) return null;
  const today = dayjs(dayjs().tz(SITE_TZ).format("YYYY-MM-DD"));
  return dayjs(due).diff(today, "day");
}

/** 기한 표기: 지났으면 "30일 경과", 남았으면 "D-3", 당일은 "오늘" */
export function dueLabel(days: number | null): string | null {
  if (days === null) return null;
  if (days < 0) return `${-days}일 경과`;
  if (days === 0) return "오늘";
  return `D-${days}`;
}

/** 촘촘한 목록용 날짜 MM-DD. 시각이 섞여 오면 기준시로 날짜만 뽑는다 */
export function shortDate(value: string | null | undefined): string {
  if (!value) return "-";
  const d = /^\d{4}-\d{2}-\d{2}$/.test(value) ? dayjs(value) : dayjs(value).tz(SITE_TZ);
  return d.isValid() ? d.format("MM-DD") : "-";
}
