// action.ts — 감소대책 표시용 순수 함수
import type { SuggestedAction } from "@/types/action";

/** 담당 기본값. 가상 사업장의 안전 업무 부서다 */
export const DEFAULT_OWNER = "관리부";

/** 근거 한 줄: 조문(제목) + 지침 번호 */
export function basisLine(s: SuggestedAction | null): string | null {
  if (!s) return null;
  const law = s.lawTitle ? `${s.lawRef}(${s.lawTitle})` : s.lawRef;
  return s.guideRef ? `${law}, KOSHA GUIDE ${s.guideRef}` : law;
}
