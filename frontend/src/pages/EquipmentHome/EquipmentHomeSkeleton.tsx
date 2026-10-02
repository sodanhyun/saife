// EquipmentHomeSkeleton.tsx — 실제 레이아웃과 같은 골격(제목, KPI 다섯 칸, 오늘 할 일 6행, 설비 제목줄과 카드 6개)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function EquipmentHomeSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="mb-5 h-8 w-32" />
      <div className="mb-5 grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-slate-200 sm:grid-cols-3 xl:grid-cols-5">
        {Array.from({ length: 5 }, (_, i) => (
          <div key={i} className="space-y-2 bg-white px-5 py-4">
            <Skeleton className="h-3 w-20" />
            <Skeleton className="h-8 w-12" />
          </div>
        ))}
      </div>
      <div className="mb-6 overflow-hidden rounded-xl border border-slate-200 bg-white">
        <div className="border-b border-slate-100 px-6 py-4"><Skeleton className="h-6 w-28" /></div>
        {Array.from({ length: 6 }, (_, i) => (
          <div key={i} className="flex items-center gap-5 border-b border-slate-100 px-6 py-3.5 last:border-b-0">
            <Skeleton className="h-3 w-20" />
            <Skeleton className="h-4 flex-1" />
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-8 w-20" />
          </div>
        ))}
      </div>
      <div className="mb-3 flex items-center justify-between">
        <Skeleton className="h-5 w-24" />
        <Skeleton className="h-9 w-32" />
      </div>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: 6 }, (_, i) => (
          <Skeleton key={i} className="h-44 w-full" />
        ))}
      </div>
    </PageLayout>
  );
}
