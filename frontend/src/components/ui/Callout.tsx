// src/components/ui/Callout.tsx
import cn from "@/lib/cn";
import { toneColor, type Tone } from "@/utils/statusColors";

interface CalloutProps {
  tone: Tone;
  title?: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
}

/**
 * 한 톤으로 물든 안내 상자. 사고 소환 배너(high)·되묻기 슬롯(pending)·판독 진행(progress)·
 * 데모 모드 안내(neutral)가 전부 이걸 쓴다. 아이콘 없이 색과 제목 굵기로만 말한다.
 */
export default function Callout({ tone, title, children, className }: CalloutProps) {
  const c = toneColor(tone);
  return (
    <div role="note" className={cn("rounded-lg border p-4", c.bg, c.border, className)}>
      {title && <p className={cn("text-stage font-semibold", c.text)}>{title}</p>}
      {children && <div className={cn("text-sm text-slate-700", title && "mt-1")}>{children}</div>}
    </div>
  );
}
