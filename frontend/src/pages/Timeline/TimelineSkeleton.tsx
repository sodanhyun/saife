// TimelineSkeleton.tsx — 실제 레이아웃과 같은 골격(요약 카드 + 사건 카드 3개)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function TimelineSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="h-7 w-40 mb-2" />
      <Skeleton className="h-4 w-96 mb-6" />
      <div className="space-y-3">
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-28 w-full" />
        <Skeleton className="h-28 w-full" />
        <Skeleton className="h-28 w-full" />
      </div>
    </PageLayout>
  );
}
