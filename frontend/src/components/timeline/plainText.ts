// plainText.ts — 서버 문장(시드·룰 근거)에 섞인 가운뎃점과 대시를 화면 표기 규칙대로 바꾼다.
// 백엔드도 같은 정리를 하지만, 시드 원문(공정명 등)이 그대로 오는 필드가 있어 화면 쪽 안전망을 둔다.

/** "A · B" → "A, B", "A — B" → "A, B", "절단·가공" → "절단/가공" */
export function plainText(text: string | null | undefined): string {
  if (!text) return "";
  return text
    .replace(/\s*[—–]\s*/g, ", ")
    .replace(/\s+·\s+/g, ", ")
    .replace(/·/g, "/");
}

/** "2026-07-16" → "07-16" */
export function monthDay(date: string): string {
  return date.length >= 10 ? date.slice(5, 10) : date;
}
