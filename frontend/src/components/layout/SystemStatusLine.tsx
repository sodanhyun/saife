// SystemStatusLine.tsx — 사이드바 하단 한 줄. 출처 상태는 세 화면(UC1·UC2·UC3) 공통이라
// 여기서만 전역으로 보인다. 데이터가 없으면(로딩·에러) 아무것도 그리지 않는다 — 에러 배너 금지.
import { StatusBadge } from "@/components/ui/Badge";
import RefreshButton from "@/components/ui/RefreshButton";
import { EVIDENCE_KIND_LABEL } from "@/types/evidence";
import type { SystemStatus } from "@/types/system";
import { formatDate } from "@/utils/datetime";

interface Props {
  status: SystemStatus | null;
  onRefresh?: () => void;
  refreshing?: boolean;
}

export default function SystemStatusLine({ status, onRefresh, refreshing }: Props) {
  if (!status) return null;

  const mode = !status.demoMode && status.embeddingAvailable ? "외부 API 실시간 · 벡터 검색" : "캐시 모드 · 키워드 검색";

  // 종류별 근거 개수 — 0건인 종류는 줄여 보여준다. 순서는 EVIDENCE_KIND_LABEL 선언 순서로 고정한다.
  const kindBreakdown = Object.entries(EVIDENCE_KIND_LABEL)
    .map(([kind, label]) => [label, status.evidenceByKind[kind] ?? 0] as const)
    .filter(([, count]) => count > 0);

  return (
    <div className="space-y-1 px-3 py-2 text-xs text-slate-400" aria-live="polite">
      <div className="flex items-center justify-between gap-1">
        <p>{mode}</p>
        {onRefresh && (
          <RefreshButton onClick={onRefresh} disabled={refreshing} title="시스템 상태 새로고침" className="!h-6 !w-6 !p-1" />
        )}
      </div>
      <p>근거 {status.evidenceChunkCount.toLocaleString("ko-KR")}건</p>
      {kindBreakdown.length > 0 && (
        <p>{kindBreakdown.map(([label, count]) => `${label} ${count.toLocaleString("ko-KR")}`).join(" · ")}</p>
      )}
      {status.circuitOpenHosts.length > 0 && (
        <StatusBadge tone="pending">{status.circuitOpenHosts.join(", ")} 일시 차단</StatusBadge>
      )}
      {status.lastCrawlAt && <p>최근 수집 {formatDate(status.lastCrawlAt)}</p>}
    </div>
  );
}
