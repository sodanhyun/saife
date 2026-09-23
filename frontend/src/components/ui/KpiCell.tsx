// src/components/ui/KpiCell.tsx
import cn from "@/lib/cn";
import { toneColor, type Tone } from "@/utils/statusColors";

interface KpiCellProps {
  label: string;
  value: React.ReactNode;
  subText?: string;
  /** 상태 강조 — 지정 시 값·서브텍스트에 연톤. 색만으로 강조하지 말고 subText를 같이 준다. */
  tone?: Tone;
  onClick?: () => void;
  title?: string;
  className?: string;
  id?: string;
}

/** 요약 수치 셀. onClick이 있으면 button, 없으면 정적 div. */
export default function KpiCell({ label, value, subText, tone, onClick, title, className, id }: KpiCellProps) {
  const t = tone && tone !== "neutral" ? toneColor(tone) : null;
  const cellClassName = cn(
    "bg-white border rounded-md p-3 text-left w-full",
    t ? cn(t.bg, t.border) : "border-slate-200",
    onClick && "cursor-pointer transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-slate-500 focus:ring-offset-1",
    className,
  );
  const content = (
    <>
      <div className="text-xs font-semibold text-slate-500">{label}</div>
      <div className={cn("mt-1 text-2xl font-bold tabular-nums", t ? t.text : "text-slate-900")}>{value}</div>
      {subText && <div className={cn("mt-0.5 text-xs", t ? t.text : "text-slate-400")}>{subText}</div>}
    </>
  );
  if (onClick) {
    return (
      <button type="button" id={id} title={title} onClick={onClick} className={cellClassName}>
        {content}
      </button>
    );
  }
  return (
    <div id={id} title={title} className={cellClassName}>
      {content}
    </div>
  );
}
