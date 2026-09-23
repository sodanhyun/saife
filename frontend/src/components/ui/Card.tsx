// src/components/ui/Card.tsx
import cn from "@/lib/cn";

interface CardProps {
  title?: React.ReactNode;
  description?: string;
  /** 제목 줄 우측 슬롯 — 버튼·링크 */
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  bodyClassName?: string;
}

/** 흰 배경 + slate 테두리 카드. 그림자는 card 하나만, 반경은 lg. */
export default function Card({ title, description, actions, children, className, bodyClassName }: CardProps) {
  const hasHeader = title || description || actions;
  return (
    <section className={cn("bg-white border border-slate-200 rounded-lg shadow-card", className)}>
      {hasHeader && (
        <div className="flex items-start justify-between gap-3 px-4 pt-4">
          <div className="min-w-0">
            {title && <h2 className="text-base font-semibold text-slate-900">{title}</h2>}
            {description && <p className="mt-0.5 text-xs text-slate-500">{description}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </div>
      )}
      <div className={cn("p-4", bodyClassName)}>{children}</div>
    </section>
  );
}
