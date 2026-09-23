// src/components/ui/PageLayout.tsx

import cn from "@/lib/cn";

interface PageLayoutProps {
  children: React.ReactNode;
  className?: string;
}

/**
 * 모든 페이지의 최외곽 래퍼.
 * bg-page 배경 + max-w-[1600px] 중앙 정렬 + 반응형 패딩.
 */
export default function PageLayout({ children, className }: PageLayoutProps) {
  return (
    <div className="w-full h-full flex justify-center bg-page">
      <div
        className={cn(
          "w-full max-w-[1600px] flex flex-col px-6 pt-6 pb-4 min-w-0 overflow-x-clip",
          className
        )}
      >
        {children}
      </div>
    </div>
  );
}
