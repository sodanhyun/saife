// EquipmentDetailPage.tsx — 명사(설비) 하나 위의 동사 세 개. 모든 행동은 여기서 시작해 여기로 돌아온다.
import { useState } from "react";

import { useNavigate, useParams } from "react-router-dom";

import { plainText } from "@/components/timeline/plainText";
import TimelineList from "@/components/timeline/TimelineList";
import TimelineSummary from "@/components/timeline/TimelineSummary";
import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import { useEquipmentTimeline } from "@/hooks/useEquipmentTimeline";
import EquipmentDetailSkeleton from "@/pages/Equipment/EquipmentDetailSkeleton";

export default function EquipmentDetailPage() {
  const { equipmentId: raw } = useParams<{ equipmentId: string }>();
  const navigate = useNavigate();
  // 파싱 실패(잘못된 경로)는 존재하지 않는 id로 취급한다. 백엔드가 404를 주고 아래 Callout으로 안내한다.
  const parsed = Number(raw);
  const id = Number.isFinite(parsed) ? parsed : -1;
  const { timeline, loading, loadError } = useEquipmentTimeline(id);
  const [focusId, setFocusId] = useState<string | null>(null);

  if (loading) return <EquipmentDetailSkeleton />;

  const eq = timeline?.equipment;
  const description = eq
    ? [eq.locationTag, eq.processName, eq.introducedOn ? `${eq.introducedOn} 도입` : null].filter(Boolean).map(plainText).join("  /  ")
    : undefined;

  return (
    <PageLayout>
      <PageHeader
        eyebrow={eq?.objectCode ? `설비 ${eq.objectCode}` : "설비"}
        title={eq?.name ?? "설비 상세"}
        description={description}
        actions={
          <>
            <Button size="sm" onClick={() => navigate(`/work-plan?equipmentId=${id}`)}>작업 신고</Button>
            <Button size="sm" variant="secondary" onClick={() => navigate(`/vision?equipmentId=${id}`)}>사진 점검</Button>
            <Button size="sm" variant="secondary" onClick={() => navigate(`/incident?equipmentId=${id}`)}>사고 신고</Button>
          </>
        }
      />
      {loadError && (
        <Callout tone="high">데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.</Callout>
      )}
      {timeline && (
        <div className="space-y-6">
          <TimelineSummary timeline={timeline} />
          <section aria-label="설비 이력">
            <div className="mb-3 flex items-baseline justify-between gap-3">
              <h2 className="text-base font-semibold text-slate-900">이 설비가 기억하는 것</h2>
              <p className="text-xs text-slate-400">사건을 누르면 이어진 기록만 강조됩니다. 연결은 서버가 계산합니다</p>
            </div>
            <TimelineList events={timeline.events} focusId={focusId} onFocus={setFocusId} />
          </section>
        </div>
      )}
    </PageLayout>
  );
}
