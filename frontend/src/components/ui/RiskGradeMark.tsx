// RiskGradeMark.tsx — 결과 카드의 큰 등급 표식. 배지(RiskBadge)보다 한 단계 큰, 화면의 주인공 자리 전용.
// 등급은 룰 엔진이 낸다. 호출부는 옆에 룰 근거(ruleTrace)를 반드시 같이 띄운다.
import cn from "@/lib/cn";
import { RISK_LABEL, type RiskLevel } from "@/types/domain";
import { riskColor } from "@/utils/statusColors";

interface Props {
  level: RiskLevel;
  size?: "md" | "lg";
  className?: string;
}

export default function RiskGradeMark({ level, size = "md", className }: Props) {
  const c = riskColor(level);
  return (
    <span
      aria-label={`위험성 ${RISK_LABEL[level]}`}
      className={cn(
        "grid shrink-0 place-items-center rounded-lg font-bold text-white",
        size === "lg" ? "h-14 w-14 text-2xl" : "h-10 w-10 text-lg",
        c.solid,
        className,
      )}
    >
      {RISK_LABEL[level]}
    </span>
  );
}
