// src/components/ui/FormField.tsx

import { Children, cloneElement, isValidElement, useId } from "react";

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
  const generatedId = useId();
  // 자식이 입력 요소 하나면 label htmlFor로 묶는다 — 스크린리더·getByLabelText가 이름을 찾는다.
  // 자식이 명시한 id가 있으면 그것을 존중한다. 여러 자식이면 기존처럼 묶지 않는다.
  let fieldId: string | undefined;
  let content = children;
  if (Children.count(children) === 1 && isValidElement<{ id?: string }>(children)) {
    fieldId = children.props.id ?? generatedId;
    content = cloneElement(children, { id: fieldId });
  }
  return (
    <div className={cn("flex flex-col", className)}>
      {label && (
        <label htmlFor={fieldId} className="text-xs font-semibold text-slate-500 mb-1 block">
          {label}
          {required && <span className="ml-0.5 text-risk-high-text">*</span>}
          {hint && <span className="ml-1 font-normal text-slate-400">{hint}</span>}
        </label>
      )}
      {content}
      {error && (
        <p className="text-xs text-risk-high-text mt-1">{error}</p>
      )}
    </div>
  );
}
