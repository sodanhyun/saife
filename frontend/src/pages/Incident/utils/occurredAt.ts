// occurredAt.ts — 발생 일시 표시(24시간제, 요일 포함). 브라우저 지역 설정의 오전/오후 표기를 쓰지 않는다.
const WEEKDAY = ["일", "월", "화", "수", "목", "금", "토"];

/** "2026-10-02T10:20" → "2026-10-02 (금) 10:20" */
export function display24(value: string): string {
  if (!value) return "일시 선택";
  const [d, t] = value.split("T");
  const [y, m, day] = d.split("-").map(Number);
  if (!y || !m || !day) return value;
  const w = WEEKDAY[new Date(y, m - 1, day).getDay()];
  return t ? `${d} (${w}) ${t.slice(0, 5)}` : `${d} (${w})`;
}

