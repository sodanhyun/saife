// TimelinePage.tsx — 설비를 골라 이력을 본다(메뉴에는 없다. 옛 링크 호환). 설비 상세와 같은 머리와 이력을 쓴다.
import { useState } from "react";

import TimelineList from "@/components/timeline/TimelineList";
import TimelineSummary from "@/components/timeline/TimelineSummary";
import Callout from "@/components/ui/Callout";
import LinkButton from "@/components/ui/LinkButton";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import Select from "@/components/ui/Select";
import { useEquipmentTimeline } from "@/hooks/useEquipmentTimeline";
import TimelineSkeleton from "@/pages/Timeline/TimelineSkeleton";
import { plainText } from "@/utils/plainText";

export default function TimelinePage() {
  const { equipment, equipmentId, setEquipmentId, timeline, loading, loadError } = useEquipmentTimeline();
  const [focusId, setFocusId] = useState<string | null>(null);
  if (loading) return <TimelineSkeleton />;
  return (
    <PageLayout>
      <PageHeader title="설비 이력"
        actions={
          <>
            <Select aria-label="설비" className="w-72" value={equipmentId ?? ""}
              onChange={(e) => { setEquipmentId(Number(e.target.value)); setFocusId(null); }}>
              {equipment.map((e) => <option key={e.id} value={e.id}>{e.name}{e.locationTag ? ` (${plainText(e.locationTag)})` : ""}</option>)}
            </Select>
            {equipmentId !== null && <LinkButton href={`/equipment/${equipmentId}`}>설비 상세</LinkButton>}
          </>
        } />
      {loadError ? (
        <Callout tone="high">데이터를 불러오지 못했습니다. 새로고침하세요.</Callout>
      ) : (
        timeline && (
          <div key={timeline.equipment.id} className="space-y-6">
            <TimelineSummary timeline={timeline} showName />
            <section aria-label="이력">
              <h2 className="mb-3 text-base font-semibold text-slate-900">이력</h2>
              <TimelineList events={timeline.events} focusId={focusId} onFocus={setFocusId} />
            </section>
          </div>
        )
      )}
    </PageLayout>
  );
}
