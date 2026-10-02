// src/components/ui/LoadErrorCallout.tsx
import Button from "@/components/ui/Button";
import cn from "@/lib/cn";
import { toneColor } from "@/utils/statusColors";

interface Props {
  /** 다시 불러오기. 없으면 문서를 새로 받는다 */
  onRetry?: () => void;
  className?: string;
}

/**
 * 조회 실패 안내. 모든 화면이 같은 문구("불러오지 못했습니다.")와 같은 버튼("새로고침")을 쓴다.
 * 개발 용어(백엔드, 서버, 상태 코드)를 화면에 내지 않는다. 오류일 때는 빈 상태를 같이 그리지 않는다.
 */
export default function LoadErrorCallout({ onRetry, className }: Props) {
  const c = toneColor("high");
  return (
    <div role="alert" className={cn("flex items-center justify-between gap-3 rounded-lg border px-4 py-3", c.bg, c.border, className)}>
      <p className={cn("text-sm font-semibold", c.text)}>불러오지 못했습니다.</p>
      <Button variant="secondary" size="sm" onClick={() => (onRetry ? onRetry() : window.location.reload())}>새로고침</Button>
    </div>
  );
}
