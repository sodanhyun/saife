import { forwardRef } from "react";

import cn from "@/lib/cn";

interface TextareaProps extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
  error?: boolean;
}

/** Input과 같은 테두리·포커스 규격의 여러 줄 입력. 대화 입력창·재해 경위에 쓴다. */
const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ error = false, className, ...props }, ref) => (
    <textarea
      ref={ref}
      aria-invalid={error || undefined}
      className={cn(
        "w-full border rounded-lg text-sm px-3 py-2 outline-none transition-colors resize-y",
        error
          ? "border-risk-high-border focus:border-risk-high focus:ring-1 focus:ring-risk-high"
          : "border-slate-300 focus:border-slate-500 focus:ring-1 focus:ring-slate-500",
        "disabled:bg-slate-50 disabled:text-slate-400",
        className,
      )}
      {...props}
    />
  ),
);

Textarea.displayName = "Textarea";

export default Textarea;
