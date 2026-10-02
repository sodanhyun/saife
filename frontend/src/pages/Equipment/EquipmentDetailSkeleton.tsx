// EquipmentDetailSkeleton.tsx — 실제 레이아웃과 같은 골격(제목과 버튼 셋, 요약 카드, "이력" 제목, 날짜 열이 있는 사건 4개)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export function HistorySkeletonRows({ rows = 4 }: { rows?: number }) {
  return (
    <div className="space-y-3">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="grid grid-cols-[6rem_3.5rem_minmax(0,1fr)] items-start">
          <div className="flex flex-col items-end gap-1.5 pt-3">
            <Skeleton className="h-4 w-12" />
            <Skeleton className="h-3 w-14" />
          </div>
          <div className="flex justify-center pt-3"><Skeleton className="h-7 w-7" /></div>
          <Skeleton className="h-20 w-full" />
        </div>
      ))}
    </div>
  );
}

export default function EquipmentDetailSkeleton() {
  return (
    <PageLayout>
      <div className="mb-5 flex items-center justify-between gap-3">
        <Skeleton className="h-8 w-48" />
        <div className="flex gap-2">
          <Skeleton className="h-8 w-24" />
          <Skeleton className="h-8 w-20" />
          <Skeleton className="h-8 w-20" />
        </div>
      </div>
      <Skeleton className="mb-6 h-44 w-full" />
      <Skeleton className="mb-3 h-5 w-12" />
      <HistorySkeletonRows />
    </PageLayout>
  );
}
