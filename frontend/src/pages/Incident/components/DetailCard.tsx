// DetailCard.tsx — 연쇄 상세 3장(수시평가·조사표 기한·작업계획서 경고)이 공유하는 틀.
import cn from "@/lib/cn";

interface Props {
  id?: string;
  label: string;
  title: React.ReactNode;
  note?: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}

export function DetailCard({ id, label, title, note, actions, children, className }: Props) {
  return (
    <section
      id={id}
      aria-label={label}
      className={cn("flex scroll-mt-6 flex-col rounded-xl border border-slate-200 bg-white shadow-card", className)}
    >
      <header className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
        <div className="min-w-0">
          <p className="text-xs font-bold tracking-wide text-slate-500">{label}</p>
          <h3 className="mt-0.5 text-base font-semibold text-slate-900">{title}</h3>
          {note && <p className="mt-1 text-xs text-slate-500">{note}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </header>
      <div className="flex-1 px-5 py-4">{children}</div>
    </section>
  );
}
