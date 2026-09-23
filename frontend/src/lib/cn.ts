// clsx + tailwind-merge 래퍼. className 병합은 이 함수로만 한다(외부 className은 마지막 인자).
import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export default cn;
