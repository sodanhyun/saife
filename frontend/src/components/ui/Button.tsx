// src/components/ui/Button.tsx

import { forwardRef } from "react";

import cn from "@/lib/cn";

const variantStyles = {
  primary: "bg-slate-900 hover:bg-slate-800 text-white rounded-lg",
  secondary: "bg-white text-slate-700 border border-slate-300 hover:bg-slate-50 rounded-lg",
  danger: "bg-risk-high hover:bg-risk-high-text text-white rounded-lg",
  ghost: "text-slate-500 hover:text-slate-700 hover:bg-slate-100 rounded-lg",
  // 테이블 행 액션 전용 — 아이콘 없이 글자만 두고 옅은 면으로 버튼임을 알린다(dense 행에서 시각 소음 최소화).
  subtle: "bg-slate-100 text-slate-600 hover:bg-slate-200 hover:text-slate-900 rounded-md",
  link: "text-slate-700 hover:text-slate-900 font-medium",
} as const;

const sizeStyles = {
  sm: "px-3 py-1.5 text-xs",
  md: "px-5 py-2 text-sm font-semibold",
  lg: "px-6 py-3 text-sm font-semibold",
} as const;

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: keyof typeof variantStyles;
  size?: keyof typeof sizeStyles;
  loading?: boolean;
}

const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ variant = "primary", size = "md", loading = false, className, children, disabled, ...props }, ref) => {
    return (
      <button
        ref={ref}
        className={cn(
          // whitespace-nowrap: 좁은 툴바/행에서도 버튼 텍스트(이력·편집·저장·적용 등) 줄바꿈 절대 금지
          // shrink-0: flex 컨테이너(툴바/액션 행)에서 버튼이 콘텐츠보다 좁게 찌부러지지 않게 유지
          "inline-flex items-center justify-center whitespace-nowrap shrink-0 transition-colors duration-200 disabled:bg-slate-300 disabled:cursor-not-allowed disabled:text-white",
          variantStyles[variant],
          sizeStyles[size],
          className
        )}
        disabled={disabled || loading}
        {...props}
      >
        {loading ? "처리 중…" : children}
      </button>
    );
  }
);

Button.displayName = "Button";

export default Button;
