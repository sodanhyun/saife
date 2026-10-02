// WorkPlanSkeleton.tsx — 대화 전 레이아웃과 같은 골격(제목, 설비 기록, 입력창, 점검 기록)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function WorkPlanSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="h-8 w-40 mb-6" />
      <div className="max-w-4xl space-y-4">
        <Skeleton className="h-28 w-full" />
        <Skeleton className="h-24 w-full" />
      </div>
      <Skeleton className="mt-10 h-48 w-full" />
    </PageLayout>
  );
}
