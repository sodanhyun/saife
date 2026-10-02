// clsx + tailwind-merge 래퍼. className 병합은 이 함수로만 한다(외부 className은 마지막 인자).
import { clsx, type ClassValue } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

// 커스텀 글자 크기(text-stage/headline/display)를 글자 색으로 오인해 색 클래스와 함께 넘기면
// 크기 클래스가 조용히 사라진다. 크기 그룹으로 알려 준다.
const twMerge = extendTailwindMerge({
  extend: { classGroups: { "font-size": [{ text: ["stage", "headline", "display"] }] } },
});

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export default cn;
