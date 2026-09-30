// 근거 카드 공통 파생값. origin 표기·톤·유사도 라벨을 여기서만 만든다(컴포넌트에서 직접 분기 금지).
import dayjs from "dayjs";

import type { Evidence } from "@/types/evidence";
import type { Tone } from "@/utils/statusColors";

/** Review Focus 5 — origin 배지 문구. LIVE=시각, CACHE=날짜, KEYWORD_FALLBACK=고정 문구 */
export function originLabel(e: Evidence): string {
  if (e.origin === "LIVE") return `실시간 조회 ${dayjs(e.fetchedAt).format("HH:mm")}`;
  if (e.origin === "CACHE") return `캐시 ${dayjs(e.fetchedAt).format("MM-DD")}`;
  return "키워드 검색";
}

export function originTone(e: Evidence): Tone {
  return e.origin === "LIVE" ? "progress" : e.origin === "KEYWORD_FALLBACK" ? "pending" : "neutral";
}

/**
 * 유사도는 사례·지침에만 의미가 있다. 조문·MSDS는 null.
 *
 * F2/R49: 키워드 폴백은 임베딩 유사도가 아니라 tsquery 순위라 0~1 스케일이 아니다 —
 * 숫자를 붙이면 근거 없는 정밀도를 주장하게 되므로 아예 감춘다. 백엔드 부스트가
 * 0~1 스코어에 가산돼 100%를 넘는 값이 나올 수 있어(F2) 화면에서 한 번 더 clamp한다.
 */
export function scoreLabel(e: Evidence): string | null {
  if (e.kind === "LAW" || e.kind === "MSDS") return null;
  if (e.origin === "KEYWORD_FALLBACK") return null;
  const pct = Math.min(100, Math.round(e.score * 100));
  return `유사도 ${pct}%`;
}
