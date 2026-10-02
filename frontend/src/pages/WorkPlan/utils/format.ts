// format.ts — 작업 전 점검 화면의 짧은 날짜 표기. 촘촘한 목록은 MM-DD, 승인 시각은 MM-DD HH:mm.
import { formatDate, formatDateTime } from "@/utils/datetime";

/** "2026-10-03" → "10-03" */
export function formatShortDate(value: string | null | undefined): string {
  const d = formatDate(value);
  return d.length === 10 ? d.slice(5) : d;
}

/** ISO 시각 → "10-02 08:50" */
export function formatShortDateTime(iso: string | null | undefined): string {
  const d = formatDateTime(iso);
  return d.length === 16 ? d.slice(5) : d;
}

/** 공단 사례 제목 앞의 분류 꼬리표("[추락] [6/19, 경남 거제시]")를 뗀다. 원문 분류는 옛 용어라 화면에 두지 않는다 */
export function caseTitle(title: string): string {
  return title.replace(/^(\s*\[[^\]]*\]\s*)+/, "").trim() || title;
}
