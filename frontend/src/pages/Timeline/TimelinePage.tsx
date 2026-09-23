import { useState } from "react";

import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import Select from "@/components/ui/Select";
import TimelineList from "@/pages/Timeline/components/TimelineList";
import TimelineSummary from "@/pages/Timeline/components/TimelineSummary";
import { useTimeline } from "@/pages/Timeline/hooks/useTimeline";
import TimelineSkeleton from "@/pages/Timeline/TimelineSkeleton";

/** UC4 — 이 화면이 증명하는 건 기능이 아니라 구조다. 전부 같은 설비 ID에 매달려 있다. */
export default function TimelinePage() {
  const { equipment, equipmentId, setEquipmentId, timeline, loading } = useTimeline();
  const [focusId, setFocusId] = useState<string | null>(null);
  if (loading) return <TimelineSkeleton />;
  return (
    <PageLayout>
      <PageHeader title="설비 타임라인" description="평가 → 작업계획서 → 사고 → 재평가가 하나의 설비 ID 위에서 이어집니다."
        actions={<Select className="w-72" value={equipmentId ?? ""} onChange={(e) => { setEquipmentId(Number(e.target.value)); setFocusId(null); }}>
          {equipment.map((e) => <option key={e.id} value={e.id}>{e.name} — {e.locationTag ?? "-"}</option>)}
        </Select>} />
      {timeline && (
        <div className="space-y-5">
          <TimelineSummary timeline={timeline} />
          <TimelineList events={timeline.events} focusId={focusId} onFocus={setFocusId} />
          <p className="text-xs text-slate-500">사건을 클릭하면 연결된 기록이 함께 강조됩니다. 연결 관계는 서버가 계산한 것입니다.</p>
        </div>
      )}
    </PageLayout>
  );
}
