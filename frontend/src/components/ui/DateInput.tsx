// src/components/ui/DateInput.tsx
// 날짜·일시 입력. 브라우저 기본 표시는 OS 지역 설정을 따라 "2026-10-02 () 오후"처럼 요일이 빠지거나
// 화면마다 모양이 달라진다(2026-10-02 녹화 환경 실측). 보이는 글자는 우리가 포맷하고,
// 고르는 동작만 투명한 기본 입력에 맡긴다.
import { CalendarDays } from "lucide-react";

import cn from "@/lib/cn";

interface Props {
  type: "date" | "datetime-local";
  value: string;
  onChange: (value: string) => void;
  "aria-label"?: string;
  className?: string;
}

const WEEKDAY = ["일", "월", "화", "수", "목", "금", "토"];

function display(type: Props["type"], value: string): string {
  if (!value) return "날짜 선택";
  const [d, t] = value.split("T");
  const [y, m, day] = d.split("-").map(Number);
  if (!y || !m || !day) return value;
  const w = WEEKDAY[new Date(y, m - 1, day).getDay()];
  const date = `${d} (${w})`;
  if (type === "date" || !t) return date;
  const [hh, mm] = t.split(":").map(Number);
  const ampm = hh < 12 ? "오전" : "오후";
  const h12 = hh % 12 === 0 ? 12 : hh % 12;
  return `${date} ${ampm} ${String(h12).padStart(2, "0")}:${String(mm).padStart(2, "0")}`;
}

export default function DateInput({ type, value, onChange, className, ...rest }: Props) {
  return (
    <div className={cn("relative flex items-center rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus-within:border-slate-500 focus-within:ring-1 focus-within:ring-slate-500", className)}>
      <span aria-hidden className={cn("flex-1 tabular-nums", !value && "text-slate-400")}>{display(type, value)}</span>
      <CalendarDays aria-hidden size={16} className="text-slate-400" />
      <input
        type={type}
        value={value}
        aria-label={rest["aria-label"]}
        onChange={(e) => onChange(e.target.value)}
        onClick={(e) => { try { e.currentTarget.showPicker?.(); } catch { /* 지원하지 않는 브라우저는 기본 동작 */ } }}
        className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
      />
    </div>
  );
}
