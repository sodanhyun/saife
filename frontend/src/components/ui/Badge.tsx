import cn from "@/lib/cn";
import type { RiskLevel } from "@/types/domain";
import { RISK_LABEL } from "@/types/domain";
import { riskColor, toneColor, type Tone } from "@/utils/statusColors";

// 알약이 아니라 각진 태그 — 연한 배경+테두리+알약 반경은 AI 생성 대시보드의 기본값이라
// 전 화면에 그 인상이 번진다. 형태(반경·타이포)로 dense 콘솔 톤을 유지한다.
const BADGE_BASE =
  "inline-flex items-center whitespace-nowrap text-xs font-bold tracking-wide px-1.5 py-0.5 rounded border";

interface BadgeProps extends Omit<React.ComponentPropsWithoutRef<"span">, "className" | "children"> {
  variant?: Tone;
  children: React.ReactNode;
  className?: string;
}

/** 범용 배지 — 기본은 무채색. 의미가 있는 상태에만 tone을 준다. */
export function Badge({ variant = "neutral", children, className, ...rest }: BadgeProps) {
  return (
    <span {...rest} className={cn(BADGE_BASE, toneColor(variant).chip, className)}>
      {children}
    </span>
  );
}

/** 위험성 등급 배지 — 등급은 룰 엔진이 낸다. 호출부는 옆에 룰 트레이스를 같이 띄운다. */
export function RiskBadge({ level, className }: { level: RiskLevel; className?: string }) {
  return (
    <span className={cn(BADGE_BASE, riskColor(level).chip, className)}>{RISK_LABEL[level]}</span>
  );
}

/** 상태 배지 — tone은 statusColors의 *Tone 함수로만 정한다. */
export function StatusBadge({ tone, children, className }: { tone: Tone; children: React.ReactNode; className?: string }) {
  return <span className={cn(BADGE_BASE, toneColor(tone).chip, className)}>{children}</span>;
}
