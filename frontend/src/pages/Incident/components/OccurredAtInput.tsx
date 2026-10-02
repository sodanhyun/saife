// OccurredAtInput.tsx — 발생 일시. 24시간제로 보이고 지금 이후는 고를 수 없다(max).
// 보이는 글자는 우리가 포맷하고(브라우저 지역 설정의 오전/오후 표기를 쓰지 않는다), 고르는 동작만 기본 입력에 맡긴다.
import { CalendarDays } from "lucide-react";

import cn from "@/lib/cn";
import { display24 } from "@/pages/Incident/utils/occurredAt";

interface Props {
  id?: string;
  value: string;
  max: string;
  onChange: (value: string) => void;
  invalid?: boolean;
  "aria-label"?: string;
}

export default function OccurredAtInput({ id, value, max, onChange, invalid = false, ...rest }: Props) {
  return (
    <div
      className={cn(
        "relative flex items-center rounded-lg border bg-white px-3 py-2 text-sm text-slate-900 focus-within:ring-1",
        invalid
          ? "border-risk-high-border focus-within:border-risk-high focus-within:ring-risk-high"
          : "border-slate-300 focus-within:border-slate-500 focus-within:ring-slate-500",
      )}
    >
      <span aria-hidden className={cn("flex-1 tabular-nums", !value && "text-slate-400")}>
        {display24(value)}
      </span>
      <CalendarDays aria-hidden size={16} className="text-slate-400" />
      <input
        id={id}
        type="datetime-local"
        value={value}
        max={max}
        aria-label={rest["aria-label"]}
        aria-invalid={invalid || undefined}
        onChange={(e) => onChange(e.target.value)}
        onClick={(e) => {
          try {
            e.currentTarget.showPicker?.();
          } catch {
            /* 지원하지 않는 브라우저는 기본 동작 */
          }
        }}
        className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
      />
    </div>
  );
}
