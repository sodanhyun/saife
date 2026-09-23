// 시각 표시는 브라우저 로컬이 아니라 Asia/Seoul로 고정한다. 법정 기한 계산이 KST 기준이다.
import dayjs from "dayjs";
import timezone from "dayjs/plugin/timezone";
import utc from "dayjs/plugin/utc";

dayjs.extend(utc);
dayjs.extend(timezone);

export const SITE_TZ = "Asia/Seoul";
const EMPTY = "-";
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/** LocalDate는 벽시계 날짜라 변환하지 않는다. Instant가 섞여 오면 기준시로 날짜만 뽑는다. */
export function formatDate(value: string | null | undefined): string {
  if (!value) return EMPTY;
  if (DATE_ONLY.test(value)) return value;
  const d = dayjs(value);
  return d.isValid() ? d.tz(SITE_TZ).format("YYYY-MM-DD") : EMPTY;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  const d = dayjs(iso);
  return d.isValid() ? d.tz(SITE_TZ).format("YYYY-MM-DD HH:mm") : EMPTY;
}

/** datetime-local 기본값 — 지금(브라우저 로컬) */
export function nowLocalInput(): string {
  return dayjs().format("YYYY-MM-DDTHH:mm");
}

/**
 * datetime-local(오프셋 없음) → 오프셋이 붙은 ISO. 오프셋 없이 보내면 서버가 자기 시간대로 해석해
 * 법정 기한이 하루 밀릴 수 있다.
 */
export function toOffsetIso(local: string): string {
  const offsetMin = -new Date(local).getTimezoneOffset();
  const sign = offsetMin >= 0 ? "+" : "-";
  const pad = (n: number) => String(Math.floor(Math.abs(n))).padStart(2, "0");
  return `${local}:00${sign}${pad(offsetMin / 60)}:${pad(offsetMin % 60)}`;
}

/** 남은 일수 → D-day 표기. 음수는 기한이 지났다. */
export function dDayLabel(daysRemaining: number | null | undefined): string {
  if (daysRemaining === null || daysRemaining === undefined) return "";
  if (daysRemaining === 0) return "D-Day";
  return daysRemaining > 0 ? `D-${daysRemaining}` : `D+${Math.abs(daysRemaining)}`;
}
