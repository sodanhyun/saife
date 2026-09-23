// src/components/ui/SectionTitle.tsx
import cn from "@/lib/cn";

/** 카드 밖 섹션 제목 — 목록 위 등. 페이지 제목보다 한 단계 아래. */
export default function SectionTitle({ children, className }: { children: React.ReactNode; className?: string }) {
  return <h2 className={cn("text-sm font-semibold text-slate-600", className)}>{children}</h2>;
}
