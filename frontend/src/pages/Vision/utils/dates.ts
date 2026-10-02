// dates.ts — 감소대책 기한 계산. 사업장 기준시(KST)로 센다.
import dayjs from "dayjs";

import { SITE_TZ } from "@/utils/datetime";

/** 감소대책 기한 기본값(오늘부터 며칠) */
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
