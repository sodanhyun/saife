// EquipmentDetailPage.tsx — 설비 하나. 머리(등급과 수치), 이력, 우측 상단의 행동 셋.
import { useEffect, useState } from "react";

import { useNavigate, useParams, useSearchParams } from "react-router-dom";

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
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  // 파싱 실패(잘못된 경로)는 존재하지 않는 id로 취급한다. 백엔드가 404를 주고 아래 Callout으로 안내한다.
  const parsed = Number(raw);
  const id = Number.isFinite(parsed) ? parsed : -1;
  const { timeline, loading, loadError } = useEquipmentTimeline(id);
  // 홈 "조치 확인"처럼 특정 사건을 들고 들어오면(?focus=action-1) 그 사건을 강조한 채로 연다
  const [focusId, setFocusId] = useState<string | null>(() => searchParams.get("focus"));

  const hasTimeline = timeline !== null && timeline !== undefined;
  useEffect(() => {
    const target = searchParams.get("focus");
    if (!hasTimeline || !target) return;
    document.getElementById(`event-${target}`)?.scrollIntoView?.({ behavior: "smooth", block: "center" });
  }, [hasTimeline, searchParams]);

  if (loading) return <EquipmentDetailSkeleton />;

  return (
    <PageLayout>
      <PageHeader
        title={timeline?.equipment.name ?? "설비"}
        actions={
          <>
            <Button size="sm" onClick={() => navigate(`/work-plan?equipmentId=${id}`)}>작업 전 점검</Button>
            <Button size="sm" variant="secondary" onClick={() => navigate(`/vision?equipmentId=${id}`)}>순회점검</Button>
            <Button size="sm" variant="secondary" onClick={() => navigate(`/incident?equipmentId=${id}`)}>사고 보고</Button>
          </>
        }
      />
      {loadError && (
        <Callout tone="high">데이터를 불러오지 못했습니다. 새로고침하세요.</Callout>
      )}
      {timeline && (
        <div className="space-y-6">
          <TimelineSummary timeline={timeline} />
          <section aria-label="이력">
            <h2 className="mb-3 text-base font-semibold text-slate-900">이력</h2>
            <TimelineList events={timeline.events} focusId={focusId} onFocus={setFocusId} />
          </section>
        </div>
      )}
    </PageLayout>
  );
}
