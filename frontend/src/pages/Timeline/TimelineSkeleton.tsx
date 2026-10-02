// TimelineSkeleton.tsx — 실제 레이아웃과 같은 골격(제목과 설비 선택, 요약 카드, "이력" 제목, 날짜 열이 있는 사건 4개)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";
import { HistorySkeletonRows } from "@/pages/Equipment/EquipmentDetailSkeleton";

export default function TimelineSkeleton() {
  return (
    <PageLayout>
      <div className="mb-5 flex items-center justify-between gap-3">
        <Skeleton className="h-8 w-32" />
        <div className="flex gap-2">
          <Skeleton className="h-9 w-72" />
          <Skeleton className="h-9 w-24" />
        </div>
      </div>
      <Skeleton className="mb-6 h-44 w-full" />
      <Skeleton className="mb-3 h-5 w-12" />
      <HistorySkeletonRows />
    </PageLayout>
  );
}
