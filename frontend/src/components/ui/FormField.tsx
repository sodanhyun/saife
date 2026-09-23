// src/components/ui/FormField.tsx

import cn from "@/lib/cn";

interface FormFieldProps {
  label?: string;
  required?: boolean;
  error?: string;
  /** 라벨 옆에 옅게 붙는 보조 설명 — 형식·예시 등. */
  hint?: string;
  children: React.ReactNode;
  className?: string;
}

export default function FormField({ label, required, error, hint, children, className }: FormFieldProps) {
  return (
    <div className={cn("flex flex-col", className)}>
      {label && (
        <label className="text-xs font-semibold text-slate-500 mb-1 block">
          {label}
          {required && <span className="ml-0.5 text-risk-high-text">*</span>}
          {hint && <span className="ml-1 font-normal text-slate-400">{hint}</span>}
        </label>
      )}
      {children}
      {error && (
        <p className="text-xs text-risk-high-text mt-1">{error}</p>
      )}
    </div>
  );
}
