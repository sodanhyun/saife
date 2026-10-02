// SystemStatusLine.tsx — 사이드바 하단. 지금 무엇과 연결되어 도는지를 한눈에 보인다.
// 데이터가 없으면(로딩·에러) 아무것도 그리지 않는다. 에러 배너 금지.
import RefreshButton from "@/components/ui/RefreshButton";
import cn from "@/lib/cn";
import type { SystemStatus } from "@/types/system";

interface Props {
  status: SystemStatus | null;
  onRefresh?: () => void;
  refreshing?: boolean;
}

function Row({ on, label, value }: { on: boolean; label: string; value?: string }) {
  return (
    <div className="flex items-center gap-2">
      <span aria-hidden className={cn("h-1.5 w-1.5 shrink-0 rounded-full", on ? "bg-risk-low-border" : "bg-pending")} />
      <span className="text-slate-300">{label}</span>
      {value && <span className="ml-auto tabular-nums text-slate-400">{value}</span>}
    </div>
  );
}

export default function SystemStatusLine({ status, onRefresh, refreshing }: Props) {
  if (!status) return null;
  const live = !status.demoMode;
  return (
    <div className="space-y-1.5 px-4 py-3 text-xs" aria-live="polite">
      <div className="flex items-center justify-between">
        <p className="font-semibold text-slate-400">연결 상태</p>
        {onRefresh && <RefreshButton onClick={onRefresh} disabled={refreshing} title="시스템 상태 새로고침" className="!h-6 !w-6 !p-1" />}
      </div>
      <Row on={live} label={live ? "Gemini 라이브" : "데모 모드(고정 응답)"} />
      <Row on={status.embeddingAvailable} label={status.embeddingAvailable ? "벡터 검색" : "키워드 검색"} />
      <Row on label="근거 색인" value={`${status.evidenceChunkCount.toLocaleString("ko-KR")}건`} />
      {status.circuitOpenHosts.length > 0 && <Row on={false} label="외부 API 일시 차단" value={String(status.circuitOpenHosts.length)} />}
    </div>
  );
}
