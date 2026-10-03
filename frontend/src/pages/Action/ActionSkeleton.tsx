// ActionSkeleton.tsx: 실제 레이아웃과 같은 골격(제목, 탭과 검색 한 줄, 표)
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function ActionSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="mb-5 h-8 w-32" />
      <div className="mb-4 flex items-center justify-between gap-3">
        <Skeleton className="h-11 w-full max-w-md" />
        <Skeleton className="h-11 w-72" />
      </div>
      <Skeleton className="h-96 w-full" />
    </PageLayout>
  );
}
