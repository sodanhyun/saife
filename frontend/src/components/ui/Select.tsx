// src/components/ui/Select.tsx

import { forwardRef } from "react";

import cn from "@/lib/cn";

interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  error?: boolean;
  /** 컴팩트 밀도 — 인라인 편집행(콘솔 테이블)에서 표시행 높이에 맞춰 수직 패딩을 축소한다. */
  dense?: boolean;
}

const Select = forwardRef<HTMLSelectElement, SelectProps>(
  ({ error = false, dense = false, className, children, ...props }, ref) => {
    return (
      <select
        ref={ref}
        aria-invalid={error || undefined}
        className={cn(
          "w-full border rounded-lg text-sm outline-none transition-colors bg-white",
          dense ? "px-2 py-1" : "px-3 py-2",
          error
            ? "border-risk-high-border focus:border-risk-high focus:ring-1 focus:ring-risk-high"
            : "border-slate-300 focus:border-slate-500 focus:ring-1 focus:ring-slate-500",
          "disabled:bg-slate-50 disabled:text-slate-400",
          className
        )}
        {...props}
      >
        {children}
      </select>
    );
  }
);

Select.displayName = "Select";

export default Select;
