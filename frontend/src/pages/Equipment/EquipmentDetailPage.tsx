// EquipmentDetailPage.tsx — 설비 하나. 머리(등급과 수치), 이력(최근이 위), 우측 상단의 행동 셋.
import { useEffect, useState } from "react";

import { useNavigate, useParams, useSearchParams } from "react-router-dom";

import TimelineList from "@/components/timeline/TimelineList";
import TimelineSummary from "@/components/timeline/TimelineSummary";
import Button from "@/components/ui/Button";
import EmptyState from "@/components/ui/EmptyState";
import LoadErrorCallout from "@/components/ui/LoadErrorCallout";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import EquipmentDetailSkeleton from "@/pages/Equipment/EquipmentDetailSkeleton";
import { useEquipmentDetail } from "@/pages/Equipment/hooks/useEquipmentDetail";

export default function EquipmentDetailPage() {
  const { equipmentId: raw } = useParams<{ equipmentId: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  // 숫자가 아닌 경로는 요청하지 않고 "찾을 수 없음"으로 보인다
  const parsed = Number(raw);
  const id = raw && Number.isInteger(parsed) && parsed > 0 ? parsed : null;
  const { timeline, notFound, loading, loadError, refetch } = useEquipmentDetail(id);
  // 홈 "조치 확인"처럼 특정 사건을 들고 들어오면(?focus=action-1) 그 사건을 강조한 채로 연다
  const [focusId, setFocusId] = useState<string | null>(() => searchParams.get("focus"));

  const hasTimeline = timeline !== null;
  useEffect(() => {
    const target = searchParams.get("focus");
    if (!hasTimeline || !target) return;
    document.getElementById(`event-${target}`)?.scrollIntoView?.({ behavior: "smooth", block: "center" });
  }, [hasTimeline, searchParams]);

  if (loading) return <EquipmentDetailSkeleton />;

  if (notFound) {
    return (
      <PageLayout>
        <PageHeader title="설비" />
        <EmptyState message="설비를 찾을 수 없습니다" className="py-16"
          action={<Button onClick={() => navigate("/")}>설비 현황</Button>} />
      </PageLayout>
    );
  }

  return (
    <PageLayout>
      <PageHeader
        title={timeline?.equipment.name ?? "설비"}
        actions={id !== null && (
          <>
            <Button size="sm" onClick={() => navigate(`/work-plan?equipmentId=${id}`)}>작업 전 점검</Button>
            <Button size="sm" variant="secondary" onClick={() => navigate(`/vision?equipmentId=${id}`)}>순회점검</Button>
            <Button size="sm" variant="secondary" onClick={() => navigate(`/incident?equipmentId=${id}`)}>사고 보고</Button>
          </>
        )}
      />
      {loadError && <LoadErrorCallout onRetry={refetch} />}
      {timeline && !loadError && (
        <div className="space-y-6">
          <TimelineSummary timeline={timeline} />
          <section aria-label="이력">
            <SectionTitle className="mb-3">이력</SectionTitle>
            <TimelineList events={timeline.events} focusId={focusId} onFocus={setFocusId} />
          </section>
        </div>
      )}
    </PageLayout>
  );
}
