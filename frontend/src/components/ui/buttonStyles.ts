// src/components/ui/buttonStyles.ts
// Button과 LinkButton이 같은 모양을 쓰도록 클래스 문자열을 한곳에 둔다
// (컴포넌트 파일에서 비컴포넌트를 export하면 react-refresh 규칙에 걸리므로 분리).

import cn from "@/lib/cn";

export const buttonVariantStyles = {
  primary: "bg-slate-900 hover:bg-slate-800 text-white rounded-lg",
  secondary: "bg-white text-slate-700 border border-slate-300 hover:bg-slate-50 rounded-lg",
  danger: "bg-risk-high hover:bg-risk-high-text text-white rounded-lg",
  ghost: "text-slate-500 hover:text-slate-700 hover:bg-slate-100 rounded-lg",
  // 테이블 행 액션 전용 — 아이콘 없이 글자만 두고 옅은 면으로 버튼임을 알린다(dense 행에서 시각 소음 최소화).
  subtle: "bg-slate-100 text-slate-600 hover:bg-slate-200 hover:text-slate-900 rounded-md",
  link: "text-slate-700 hover:text-slate-900 font-medium",
} as const;

export const buttonSizeStyles = {
  sm: "px-3 py-1.5 text-xs",
  md: "px-5 py-2 text-sm font-semibold",
  lg: "px-6 py-3 text-sm font-semibold",
} as const;

export type ButtonVariant = keyof typeof buttonVariantStyles;
export type ButtonSize = keyof typeof buttonSizeStyles;

/** 버튼 외형 클래스 — whitespace-nowrap: 버튼 텍스트 줄바꿈 금지, shrink-0: flex 안에서 찌부러지지 않게 */
export function buttonClassName(variant: ButtonVariant, size: ButtonSize): string {
  return cn(
    "inline-flex items-center justify-center whitespace-nowrap shrink-0 transition-colors duration-200 disabled:bg-slate-300 disabled:cursor-not-allowed disabled:text-white",
    buttonVariantStyles[variant],
    buttonSizeStyles[size],
  );
}
