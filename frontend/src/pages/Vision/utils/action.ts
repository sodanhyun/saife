// action.ts — 개선대책 표시용 순수 함수
import type { SuggestedAction } from "@/types/action";

/** 근거 한 줄: 조문(제목) + 지침 번호. 법령 이름은 줄여 쓴다 */
export function basisLine(s: SuggestedAction | null): string | null {
  if (!s) return null;
  const ref = s.lawRef.replace("산업안전보건기준에 관한 규칙", "안전보건규칙");
  const law = s.lawTitle ? `${ref}(${s.lawTitle})` : ref;
  return s.guideRef ? `${law}, KOSHA GUIDE ${s.guideRef}` : law;
}
