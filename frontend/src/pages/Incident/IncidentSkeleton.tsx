// IncidentSkeleton.tsx — 실제 레이아웃과 같은 골격(제목 2줄 + 폼 카드 + 목록)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function IncidentSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="h-7 w-40 mb-2" />
      <Skeleton className="h-4 w-96 mb-6" />
      <div className="space-y-6">
        <Skeleton className="h-64 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    </PageLayout>
  );
}
