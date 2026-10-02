// VisionSkeleton.tsx — 실제 레이아웃과 같은 골격: 제목, 점검 정보 줄(설비, 점검자, 참여 근로자),
// 사진 5 : 최근 점검 6. 최근 점검은 제목 줄과 행 다섯 개다.
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

function Field({ className }: { className?: string }) {
  return (
    <div className={className}>
      <Skeleton className="mb-1.5 h-3 w-14" />
      <Skeleton className="h-9 w-full" />
    </div>
  );
}

export default function VisionSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="mb-5 h-8 w-32" />
      <div className="mb-5 grid grid-cols-[minmax(0,18rem)_minmax(0,11rem)_minmax(0,1fr)] gap-4 rounded-xl border border-slate-200 bg-white px-5 py-4 shadow-card">
        <Field />
        <Field />
        <Field />
      </div>
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
        <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-card">
          <Skeleton className="aspect-video w-full" />
        </div>
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-card">
          <div className="border-b border-slate-100 px-5 py-3.5"><Skeleton className="h-5 w-20" /></div>
          {Array.from({ length: 5 }, (_, i) => (
            <div key={i} className="flex items-center gap-4 border-b border-slate-100 px-5 py-3 last:border-b-0">
              <Skeleton className="h-4 w-10" />
              <div className="flex-1 space-y-1.5"><Skeleton className="h-4 w-40" /><Skeleton className="h-3 w-24" /></div>
              <Skeleton className="h-5 w-40" />
            </div>
          ))}
        </div>
      </div>
    </PageLayout>
  );
}
