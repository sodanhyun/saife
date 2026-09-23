// src/components/ui/PageSkeleton.tsx
import Skeleton from "@/components/ui/Skeleton";

// 페이지 지연 로딩(Suspense) 범용 스켈레톤 — "로딩.." 텍스트 대신 레이아웃 형태 유지(프로젝트 스켈레톤 기조).
export default function PageSkeleton() {
  return (
    <div className="max-w-[1600px] mx-auto px-6 py-6" aria-hidden="true">
      <Skeleton className="h-7 w-48 mb-3" />
      <Skeleton className="h-4 w-72 mb-8" />
      <div className="space-y-3">
        <Skeleton className="h-11 w-full" />
        <Skeleton className="h-11 w-full" />
        <Skeleton className="h-11 w-full" />
        <Skeleton className="h-11 w-5/6" />
      </div>
    </div>
  );
}
