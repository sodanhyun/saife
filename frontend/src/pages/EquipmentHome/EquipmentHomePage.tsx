// EquipmentHomePage.tsx — 3초 안에 "오늘 무엇이 위험하고 무엇을 해야 하는가". 숫자 다섯, 할 일 목록, 설비 카드 순.
import { useMemo, useState } from "react";

import Callout from "@/components/ui/Callout";
import EmptyState from "@/components/ui/EmptyState";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import EquipmentCard from "@/pages/EquipmentHome/components/EquipmentCard";
import KpiStrip from "@/pages/EquipmentHome/components/KpiStrip";
import TodayInbox from "@/pages/EquipmentHome/components/TodayInbox";
import EquipmentHomeSkeleton from "@/pages/EquipmentHome/EquipmentHomeSkeleton";
import { useEquipmentCards } from "@/pages/EquipmentHome/hooks/useEquipmentCards";
import { useToday } from "@/pages/EquipmentHome/hooks/useToday";
import { buildKpis, buildTodayRows, filterRows, sortCards, type KpiKey } from "@/pages/EquipmentHome/utils/todayModel";

const GRID_ID = "equipment-grid";

export default function EquipmentHomePage() {
  const { cards, loading, loadError, refetch: refetchCards } = useEquipmentCards();
  const today = useToday();
  const [filter, setFilter] = useState<KpiKey | null>(null);

  const items = useMemo(() => today.view?.items ?? [], [today.view]);
  const rows = useMemo(() => buildTodayRows(items), [items]);
  const kpis = useMemo(() => buildKpis(items, cards), [items, cards]);
  const sorted = useMemo(() => sortCards(cards), [cards]);

  if (loading) return <EquipmentHomeSkeleton />;

  const selectKpi = (key: KpiKey) => {
    if (key === "highRisk") {
      document.getElementById(GRID_ID)?.scrollIntoView({ behavior: "smooth", block: "start" });
      return;
    }
    setFilter((cur) => (cur === key ? null : key));
  };
  const filterLabel = filter ? kpis.find((k) => k.key === filter)?.label ?? null : null;

  return (
    <PageLayout>
      <PageHeader
        eyebrow="설비 현황"
        title="오늘 위험한 것부터"
        description="모든 기록은 설비 ID 위에 쌓입니다. 기한, 승인, 법정 제출을 설비 기억에서 꺼내 오늘의 순서로 세웁니다."
      />
      {loadError && (
        <Callout tone="high" className="mb-4">
          데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.
        </Callout>
      )}
      {!today.error && today.view && <KpiStrip kpis={kpis} active={filter} onSelect={selectKpi} />}
      {!today.error && (
        <TodayInbox view={today.view} rows={filterRows(rows, filter)} filterLabel={filterLabel}
          onClearFilter={() => setFilter(null)}
          onRefresh={() => { today.refetch(); refetchCards(); }} />
      )}

      <section id={GRID_ID} aria-label="설비" className="scroll-mt-6">
        <div className="mb-3 flex items-baseline justify-between gap-3">
          <h2 className="text-base font-semibold text-slate-900">설비 {cards.length}대</h2>
          <p className="text-xs text-slate-400">등급 상, 기한 경과 순. 카드를 누르면 그 설비의 이력이 열립니다</p>
        </div>
        {!loadError && cards.length === 0 && <EmptyState message="등록된 설비가 없습니다" />}
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {sorted.map((card, i) => (
            <EquipmentCard key={card.id} card={card} index={i} />
          ))}
        </div>
      </section>
    </PageLayout>
  );
}
