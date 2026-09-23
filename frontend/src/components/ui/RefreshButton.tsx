// src/components/ui/RefreshButton.tsx
import { RefreshCw } from "lucide-react";

import cn from "@/lib/cn";
import Button from "@/components/ui/Button";

interface RefreshButtonProps {
  onClick: () => void;
  disabled?: boolean;
  title?: string;
  className?: string;
}

/**
 * 리스트 페이지 공통 새로고침 버튼.
 * disabled 상태에서 스피너 애니메이션을 표시한다.
 * 쿨다운 로직은 사용처(훅)에서 관리.
 */
export default function RefreshButton({
  onClick,
  disabled = false,
  title,
  className,
}: RefreshButtonProps) {
  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={onClick}
      disabled={disabled}
      className={cn(
        "!bg-transparent !text-slate-500 hover:!text-slate-700 hover:!bg-slate-100 disabled:!bg-transparent disabled:!text-slate-300 p-2",
        className
      )}
      title={title ?? "새로고침"}
    >
      <RefreshCw size={16} className={cn(disabled && "animate-spin")} />
    </Button>
  );
}
