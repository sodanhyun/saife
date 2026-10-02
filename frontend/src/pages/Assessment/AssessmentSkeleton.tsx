// AssessmentSkeleton.tsx — 실제 레이아웃과 같은 골격(제목과 버튼, 사고 줄, 점검 정보, 위험요인 카드)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function AssessmentSkeleton() {
  return (
    <PageLayout>
      <div className="mb-5 flex items-end justify-between">
        <Skeleton className="h-8 w-32" />
        <Skeleton className="h-9 w-56" />
      </div>
      <div className="space-y-6">
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-24 w-full" />
        <div className="space-y-3">
          <Skeleton className="h-32 w-full" />
          <Skeleton className="h-32 w-full" />
        </div>
      </div>
    </PageLayout>
  );
}
