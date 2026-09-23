// VisionSkeleton.tsx — 실제 레이아웃과 같은 2열 골격
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function VisionSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="h-7 w-56 mb-2" /><Skeleton className="h-4 w-80 mb-6" />
      <div className="grid gap-5 lg:grid-cols-[360px_minmax(0,1fr)]">
        <Skeleton className="h-96 w-full" />
        <div className="space-y-3"><Skeleton className="h-40 w-full" /><Skeleton className="h-40 w-full" /></div>
      </div>
    </PageLayout>
  );
}
