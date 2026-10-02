// IncidentSkeleton.tsx — 실제 레이아웃과 같은 골격(제목, 입력 폼 카드, 사고 이력 표)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function IncidentSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="mb-6 h-7 w-32" />
      <div className="space-y-6">
        <Skeleton className="h-48 w-full" />
        <Skeleton className="h-32 w-full" />
      </div>
    </PageLayout>
  );
}
