// EquipmentHomePage.tsx — 설비 현황. 숫자 다섯, 오늘 할 일, 설비 카드 순.
import { useMemo, useState } from "react";

import Callout from "@/components/ui/Callout";
import EmptyState from "@/components/ui/EmptyState";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import Select from "@/components/ui/Select";
import EquipmentCard from "@/pages/EquipmentHome/components/EquipmentCard";
import KpiStrip from "@/pages/EquipmentHome/components/KpiStrip";
import TodayInbox from "@/pages/EquipmentHome/components/TodayInbox";
import EquipmentHomeSkeleton from "@/pages/EquipmentHome/EquipmentHomeSkeleton";
import { useEquipmentCards } from "@/pages/EquipmentHome/hooks/useEquipmentCards";
import { useToday } from "@/pages/EquipmentHome/hooks/useToday";
import { buildKpis, buildTodayRows, filterRows, sortCards, sortCardsByName, type KpiKey } from "@/pages/EquipmentHome/utils/todayModel";

const GRID_ID = "equipment-grid";

type CardOrder = "risk" | "name";

export default function EquipmentHomePage() {
  const { cards, loading, loadError, refetch: refetchCards } = useEquipmentCards();
  const today = useToday();
  const [filter, setFilter] = useState<KpiKey | null>(null);
  const [order, setOrder] = useState<CardOrder>("risk");

  const items = useMemo(() => today.view?.items ?? [], [today.view]);
  const rows = useMemo(() => buildTodayRows(items), [items]);
  const kpis = useMemo(() => buildKpis(items, cards), [items, cards]);
  const sorted = useMemo(() => (order === "risk" ? sortCards(cards) : sortCardsByName(cards)), [cards, order]);

  if (loading) return <EquipmentHomeSkeleton />;

  const selectKpi = (key: KpiKey) => {
    if (key === "highRisk") {
      setOrder("risk");
      document.getElementById(GRID_ID)?.scrollIntoView({ behavior: "smooth", block: "start" });
      return;
    }
    setFilter((cur) => (cur === key ? null : key));
  };
  const filterLabel = filter ? kpis.find((k) => k.key === filter)?.label ?? null : null;

  return (
    <PageLayout>
      <PageHeader title="설비 현황" />
      {loadError && (
        <Callout tone="high" className="mb-4">
          데이터를 불러오지 못했습니다. 새로고침하세요.
        </Callout>
      )}
      {!today.error && today.view && <KpiStrip kpis={kpis} active={filter} onSelect={selectKpi} />}
      {!today.error && (
        <TodayInbox view={today.view} rows={filterRows(rows, filter)} filterLabel={filterLabel}
          onClearFilter={() => setFilter(null)}
          onRefresh={() => { today.refetch(); refetchCards(); }} />
      )}

      <section id={GRID_ID} aria-label="설비" className="scroll-mt-6">
        <div className="mb-3 flex items-center justify-between gap-3">
          <h2 className="text-base font-semibold text-slate-900">설비 {cards.length}대</h2>
          <Select aria-label="정렬" className="w-32" value={order} onChange={(e) => setOrder(e.target.value as CardOrder)}>
            <option value="risk">위험도순</option>
            <option value="name">이름순</option>
          </Select>
        </div>
        {!loadError && cards.length === 0 && <EmptyState message="등록된 설비 없음" />}
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {sorted.map((card, i) => (
            <EquipmentCard key={card.id} card={card} index={i} />
          ))}
        </div>
      </section>
    </PageLayout>
  );
}
