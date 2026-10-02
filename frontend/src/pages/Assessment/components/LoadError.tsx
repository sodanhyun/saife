// LoadError.tsx — 화면 재료를 못 불러왔을 때 한 줄. 빈 상태와 함께 그리지 않는다.
import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";

export default function LoadError({ onRetry }: { onRetry: () => void }) {
  return (
    <Callout tone="high">
      <div className="flex items-center gap-3">
        <span className="flex-1">불러오지 못했습니다.</span>
        <Button variant="secondary" size="sm" onClick={onRetry}>
          새로고침
        </Button>
      </div>
    </Callout>
  );
}
