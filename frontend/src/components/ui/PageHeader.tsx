// src/components/ui/PageHeader.tsx
import cn from "@/lib/cn";

interface PageHeaderProps {
  title: string;
  description?: string;
  /** 우측 슬롯: 검색, 필터, 버튼 등 */
  actions?: React.ReactNode;
  className?: string;
}

/**
 * 페이지 상단 제목 + 설명 + 액션 영역.
 * 모바일: 세로 배치, 데스크톱: 가로 배치.
 */
export default function PageHeader({ title, description, actions, className }: PageHeaderProps) {
  return (
    <div
      className={cn(
        "flex flex-col sm:flex-row justify-between items-start sm:items-center mb-4 gap-3",
        className
      )}
    >
      <div>
        <h1 className="text-xl font-bold text-slate-900">{title}</h1>
        {description && <p className="text-sm text-slate-500 mt-0.5">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
