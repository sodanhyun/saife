// TimelinePage.tsx — UC4. 이 화면이 증명하는 건 기능이 아니라 구조다. 전부 같은 설비 ID에 매달려 있다.
import { useState } from "react";

import { plainText } from "@/utils/plainText";
import TimelineList from "@/components/timeline/TimelineList";
import TimelineSummary from "@/components/timeline/TimelineSummary";
import Callout from "@/components/ui/Callout";
import LinkButton from "@/components/ui/LinkButton";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import Select from "@/components/ui/Select";
import { useEquipmentTimeline } from "@/hooks/useEquipmentTimeline";
import TimelineSkeleton from "@/pages/Timeline/TimelineSkeleton";

export default function TimelinePage() {
  const { equipment, equipmentId, setEquipmentId, timeline, loading, loadError } = useEquipmentTimeline();
  const [focusId, setFocusId] = useState<string | null>(null);
  if (loading) return <TimelineSkeleton />;
  return (
    <PageLayout>
      <PageHeader eyebrow="설비 타임라인" title="하나의 설비 ID 위의 기록"
        description="평가, 조치, 작업계획서, 사고, 수시평가, 재평가가 같은 설비 위에서 이어집니다."
        actions={
          <>
            <Select aria-label="설비 선택" className="w-72" value={equipmentId ?? ""}
              onChange={(e) => { setEquipmentId(Number(e.target.value)); setFocusId(null); }}>
              {equipment.map((e) => <option key={e.id} value={e.id}>{e.name}{e.locationTag ? ` (${plainText(e.locationTag)})` : ""}</option>)}
            </Select>
            {equipmentId !== null && <LinkButton href={`/equipment/${equipmentId}`}>설비 상세</LinkButton>}
          </>
        } />
      {loadError ? (
        <Callout tone="high">데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.</Callout>
      ) : (
        timeline && (
          <div key={timeline.equipment.id} className="space-y-6">
            <TimelineSummary timeline={timeline} showIdentity />
            <section aria-label="설비 이력">
              <div className="mb-3 flex items-baseline justify-between gap-3">
                <h2 className="text-base font-semibold text-slate-900">이 설비가 기억하는 것</h2>
                <p className="text-xs text-slate-400">사건을 누르면 이어진 기록만 강조됩니다. 연결은 서버가 계산합니다</p>
              </div>
              <TimelineList events={timeline.events} focusId={focusId} onFocus={setFocusId} />
            </section>
          </div>
        )
      )}
    </PageLayout>
  );
}
