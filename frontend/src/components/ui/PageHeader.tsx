// src/components/ui/PageHeader.tsx
import cn from "@/lib/cn";

interface PageHeaderProps {
  title: string;
  description?: string;
  /** 제목 위 작은 분류 문구(예: "UC3 작업 신고"). 화면이 어느 흐름의 몇 번째인지 알린다 */
  eyebrow?: string;
  /** 우측 슬롯: 검색, 필터, 버튼 등 */
  actions?: React.ReactNode;
  className?: string;
}

/**
 * 페이지 상단 제목 + 설명 + 액션 영역.
 * 모바일: 세로 배치, 데스크톱: 가로 배치.
 */
export default function PageHeader({ title, description, eyebrow, actions, className }: PageHeaderProps) {
  return (
    <div className={cn("mb-5 flex flex-col items-start justify-between gap-3 sm:flex-row sm:items-end", className)}>
      <div>
        {eyebrow && <p className="mb-1 text-xs font-semibold tracking-wide text-brand">{eyebrow}</p>}
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">{title}</h1>
        {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
