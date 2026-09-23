// src/components/ui/Input.tsx

import { forwardRef } from "react";

import cn from "@/lib/cn";

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  error?: boolean;
  /** 컴팩트 밀도 — 인라인 편집행(콘솔 테이블)에서 표시행 높이에 맞춰 수직 패딩을 축소한다. */
  dense?: boolean;
  /**
   * 공백을 아예 받지 않는다(앞·뒤·중간 전부). 식별번호처럼 공백이 값의 일부일 수 없는 칸에 쓴다.
   *
   * 막는 게 아니라 **걸러 낸다** — 공백이 든 문자열을 붙여넣어도 거부하지 않고 공백만 빼고 받는다.
   * 현장에서 값은 대개 다른 화면·전표에서 복사해 오는데, 통째로 거부하면 사람이 손으로 지우게 된다.
   */
  noWhitespace?: boolean;
}

/**
 * 공백 판정은 `\s`에 맡긴다 — 스페이스·탭뿐 아니라 전각 공백(U+3000)과 NBSP(U+00A0)까지 포함한다.
 * 중국어 IME와 웹에서 복사한 값에 이 둘이 섞여 들어오는데, 눈으로는 일반 공백과 구분되지 않는다.
 */
const WHITESPACE = /\s/g;

const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ error = false, dense = false, noWhitespace = false, className, onChange, ...props }, ref) => {
    /**
     * 공백 제거 후 캐럿을 제자리에 돌려놓는다. 값만 고치고 캐럿을 두면 브라우저가 끝으로 밀어 버려,
     * 문자열 가운데를 고치던 사람이 매번 커서를 다시 잡아야 한다.
     */
    const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
      if (noWhitespace) {
        const el = e.target;
        const raw = el.value;
        const stripped = raw.replace(WHITESPACE, "");
        if (stripped !== raw) {
          const caret = el.selectionStart ?? raw.length;
          const keptBeforeCaret = raw.slice(0, caret).replace(WHITESPACE, "").length;
          el.value = stripped;
          el.setSelectionRange(keptBeforeCaret, keptBeforeCaret);
        }
      }
      onChange?.(e);
    };

    return (
      <input
        ref={ref}
        aria-invalid={error || undefined}
        onChange={handleChange}
        className={cn(
          "w-full border rounded-lg text-sm outline-none transition-all",
          dense ? "px-2 py-1" : "px-3 py-2",
          error
            ? "border-risk-high-border focus:border-risk-high focus:ring-1 focus:ring-risk-high"
            : "border-slate-300 focus:border-slate-500 focus:ring-1 focus:ring-slate-500",
          "disabled:bg-slate-50 disabled:text-slate-400",
          className
        )}
        {...props}
      />
    );
  }
);

Input.displayName = "Input";

export default Input;
