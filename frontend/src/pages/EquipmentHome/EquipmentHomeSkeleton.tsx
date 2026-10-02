// EquipmentHomeSkeleton.tsx — 실제 레이아웃과 같은 골격(제목, KPI 다섯 칸, 오늘 할 일, 카드 6개)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function EquipmentHomeSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="mb-2 h-3 w-20" />
      <Skeleton className="mb-2 h-7 w-56" />
      <Skeleton className="mb-5 h-4 w-96" />
      <Skeleton className="mb-5 h-24 w-full" />
      <Skeleton className="mb-6 h-80 w-full" />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: 6 }, (_, i) => (
          <Skeleton key={i} className="h-60 w-full" />
        ))}
      </div>
    </PageLayout>
  );
}
