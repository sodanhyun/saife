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

/**
 * 공단 사례 제목을 화면 표기로 정리한다.
 * 분류 꼬리표("[추락] [6/19, 경남 거제시]", "[사망 1명]"), 원문 관리번호("(200903)")를 떼고,
 * 원문 띄어쓰기와 옛 표기("작업중", "알콜")를 바로잡는다. 원문 분류는 옛 용어라 화면에 두지 않는다.
 */
export function caseTitle(title: string): string {
  const cleaned = title
    .replace(/\[[^\]]*\]/g, " ")
    .replace(/\(\d{4,}\)/g, " ")
    .replace(/작업중/g, "작업 중")
    .replace(/알콜/g, "알코올")
    .replace(/\s{2,}/g, " ")
    .trim();
  return cleaned || title;
}
