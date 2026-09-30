/** 근거(RAG) 카드 — 백엔드 A3 Evidence 레코드와 1:1. camelCase 매핑. */

export type EvidenceKind = "CASE_FATALITY" | "CASE_DISASTER" | "GUIDE" | "LAW" | "MSDS";
export type EvidenceOrigin = "LIVE" | "CACHE" | "KEYWORD_FALLBACK";

/**
 * @property no 대화 내 근거 번호. 본문 인용("#3")과 카드가 같은 번호를 공유한다
 * @property refId 원본 레코드 PK. 법 조문처럼 PK가 없는 경우 null
 * @property refKey 사람이 읽는 원본 식별자(예: "MSDS:001032")
 * @property sourceUrl 원문(외부) 링크. 없으면 null
 * @property mediaUrl 백엔드 프록시 원본 미디어 경로(사진 원본·PDF). 프론트는 그대로 렌더링만 한다
 * @property thumbnailUrl 백엔드 프록시 썸네일 경로(`?w=320`). 없으면 null
 * @property meta kind별 부가 필드(조문의 effectiveOn·fullText, MSDS의 pictograms·sections 등)
 */
export interface Evidence {
  no: number;
  kind: EvidenceKind;
  refId: number | null;
  refKey: string;
  title: string;
  snippet: string;
  sourceUrl: string | null;
  mediaUrl: string | null;
  thumbnailUrl: string | null;
  origin: EvidenceOrigin;
  score: number;
  fetchedAt: string;
  meta: Record<string, unknown>;
}

export const EVIDENCE_KIND_LABEL: Record<EvidenceKind, string> = {
  CASE_FATALITY: "사고사망 사례",
  CASE_DISASTER: "재해 사례",
  GUIDE: "KOSHA GUIDE",
  LAW: "법 조문",
  MSDS: "MSDS",
};
