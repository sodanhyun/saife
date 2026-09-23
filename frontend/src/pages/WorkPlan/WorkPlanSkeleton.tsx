// WorkPlanSkeleton.tsx — 실제 레이아웃과 같은 2열 골격
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function WorkPlanSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="h-7 w-56 mb-2" /><Skeleton className="h-4 w-80 mb-6" />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
        <div className="space-y-3"><Skeleton className="h-80 w-full" /><Skeleton className="h-16 w-full" /><Skeleton className="h-40 w-full" /></div>
        <Skeleton className="h-96 w-full" />
      </div>
    </PageLayout>
  );
}
