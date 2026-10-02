// src/components/ui/SectionTitle.tsx
import cn from "@/lib/cn";

/**
 * 카드 밖 섹션 제목. 페이지 제목보다 한 단계 아래이고, 모든 화면이 이 한 가지 모양을 쓴다
 * (ui-styling 규칙의 섹션 제목: text-base font-semibold text-slate-900).
 */
export default function SectionTitle({ children, className }: { children: React.ReactNode; className?: string }) {
  return <h2 className={cn("text-base font-semibold text-slate-900", className)}>{children}</h2>;
}
