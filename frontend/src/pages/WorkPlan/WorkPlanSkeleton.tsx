// WorkPlanSkeleton.tsx — 대화 전 레이아웃과 같은 골격(제목, 입력창, 예시 칩, 점검 기록 머리와 표)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function WorkPlanSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="mb-6 h-8 w-40" />
      <Skeleton className="h-24 w-full" />
      <div className="mt-2.5 flex gap-2">
        <Skeleton className="h-8 w-80" />
        <Skeleton className="h-8 w-80" />
      </div>
      <div className="mt-10 mb-3 flex items-center justify-between">
        <Skeleton className="h-5 w-24" />
        <div className="flex gap-2">
          <Skeleton className="h-9 w-56" />
          <Skeleton className="h-9 w-36" />
        </div>
      </div>
      <div className="space-y-2 rounded-xl border border-slate-200 bg-white p-3">
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-9 w-full" />)}
      </div>
    </PageLayout>
  );
}
