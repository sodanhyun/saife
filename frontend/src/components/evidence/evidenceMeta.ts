// 근거 카드 공통 파생값. 출처 표기, 톤, 유사도 라벨, 제목 정리를 여기서만 만든다(컴포넌트에서 직접 분기 금지).
import dayjs from "dayjs";

import type { Evidence } from "@/types/evidence";
import { plainText } from "@/utils/plainText";
import type { Tone } from "@/utils/statusColors";

/** 출처 시점 표기. 화면에 개발 용어(캐시, 실시간)를 쓰지 않는다: 조회 시각 또는 기준일만 말한다 */
export function originLabel(e: Evidence): string {
  if (e.origin === "LIVE") return `${dayjs(e.fetchedAt).format("HH:mm")} 조회`;
  if (e.origin === "CACHE") return `${dayjs(e.fetchedAt).format("MM-DD")} 기준`;
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

/** 원문 머리표 하나: [추락], [제조업], [6/19, 경남 거제시], [사망 1명] */
const BRACKET_TAG = /^\s*\[[^\]]{1,30}\]\s*/;
/** 끝의 "(200903)" 같은 연월 코드 */
const TRAILING_CODE = /\s*\(\d{4,8}\)\s*$/;

/**
 * 사례 제목 정리. 공단 원문의 머리표("[추락]", "[6/19, 경남 거제시]", "[사망 1명]")와 연월 코드("(200903)")를 떼고,
 * 띄어쓰기와 표기를 고친다("작업중" → "작업 중", "알콜" → "알코올").
 */
export function cleanEvidenceTitle(title: string): string {
  let t = title.trim();
  let prev = "";
  while (t !== prev) {
    prev = t;
    t = t.replace(BRACKET_TAG, "");
  }
  t = t.replace(TRAILING_CODE, "");
  t = t.replace(/작업중/g, "작업 중").replace(/알콜/g, "알코올").replace(/\s{2,}/g, " ").trim();
  return plainText(t || title.trim());
}

/** 발췌 정리: 원문 본문이 비어 있던 흔적("null")을 지운다 */
export function cleanEvidenceSnippet(snippet: string | null | undefined): string {
  if (!snippet) return "";
  return plainText(snippet.replace(/\s*null\s*(…)?\s*$/, "").trim());
}
