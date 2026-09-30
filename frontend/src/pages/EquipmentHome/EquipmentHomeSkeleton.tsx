// EquipmentHomeSkeleton.tsx — 실제 레이아웃과 같은 골격(오늘 할 일 자리 + 카드 6개)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function EquipmentHomeSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="h-7 w-40 mb-2" />
      <Skeleton className="h-4 w-96 mb-6" />
      <Skeleton className="h-16 w-full mb-4" />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: 6 }, (_, i) => (
          <Skeleton key={i} className="h-52 w-full" />
        ))}
      </div>
    </PageLayout>
  );
}
