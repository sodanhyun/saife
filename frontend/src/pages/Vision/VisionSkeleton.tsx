// VisionSkeleton.tsx — 실제 레이아웃과 같은 골격(점검 정보 줄, 사진 5 : 위험요인 6)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function VisionSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="mb-5 h-8 w-40" />
      <Skeleton className="mb-5 h-20 w-full" />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
        <Skeleton className="h-[28rem] w-full" />
        <div className="space-y-4"><Skeleton className="h-64 w-full" /></div>
      </div>
    </PageLayout>
  );
}
