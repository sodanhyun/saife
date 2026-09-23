import { Badge } from "@/components/ui/Badge";
import type { ConnectionState } from "@/types/sse";

/** 문제 구간(disconnected·error)만 알린다. 잘 되고 있을 때는 화면을 어지럽히지 않는다. */
export default function SseConnectionStatus({ state, className }: { state: ConnectionState; className?: string }) {
  if (state !== "disconnected" && state !== "error") return null;
  return (
    <Badge role="status" aria-live="polite" variant="high" className={className}>
      {state === "error" ? "스트림 오류" : "연결 끊김"}
    </Badge>
  );
}
