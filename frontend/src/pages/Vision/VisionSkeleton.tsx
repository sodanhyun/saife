// VisionSkeleton.tsx — 실제 레이아웃과 같은 골격(진행 띠, 사진 5 : 후보 6)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function VisionSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="mb-2 h-4 w-32" /><Skeleton className="mb-2 h-7 w-56" /><Skeleton className="mb-5 h-4 w-96" />
      <Skeleton className="mb-5 h-16 w-full" />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
        <Skeleton className="h-[28rem] w-full" />
        <div className="space-y-4"><Skeleton className="h-40 w-full" /><Skeleton className="h-40 w-full" /></div>
      </div>
    </PageLayout>
  );
}
