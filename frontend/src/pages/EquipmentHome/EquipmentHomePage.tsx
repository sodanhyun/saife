// EquipmentHomePage.tsx — 설비 현황. 숫자 다섯, 오늘 할 일, 설비 카드 순.
import { useMemo, useState } from "react";

import { X } from "lucide-react";

import LoadErrorCallout from "@/components/ui/LoadErrorCallout";
import EmptyState from "@/components/ui/EmptyState";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import Select from "@/components/ui/Select";
import EquipmentCard from "@/pages/EquipmentHome/components/EquipmentCard";
import KpiStrip from "@/pages/EquipmentHome/components/KpiStrip";
import TodayInbox from "@/pages/EquipmentHome/components/TodayInbox";
import EquipmentHomeSkeleton from "@/pages/EquipmentHome/EquipmentHomeSkeleton";
import { useEquipmentCards } from "@/pages/EquipmentHome/hooks/useEquipmentCards";
import { useToday } from "@/pages/EquipmentHome/hooks/useToday";
import {
  buildKpis, buildTodayRows, filterHighRisk, filterRows, sortCards, sortCardsByName, type KpiKey,
} from "@/pages/EquipmentHome/utils/todayModel";

const GRID_ID = "equipment-grid";

type CardOrder = "risk" | "name";
type RowFilter = Exclude<KpiKey, "highRisk">;

export default function EquipmentHomePage() {
  const { cards, loading, loadError, refetch: refetchCards } = useEquipmentCards();
  const today = useToday();
  const [filter, setFilter] = useState<RowFilter | null>(null);
  const [highOnly, setHighOnly] = useState(false);
  const [order, setOrder] = useState<CardOrder>("risk");

  const items = useMemo(() => today.view?.items ?? [], [today.view]);
  const rows = useMemo(() => buildTodayRows(items), [items]);
  const kpis = useMemo(() => buildKpis(rows, cards), [rows, cards]);
  const shown = useMemo(() => {
    const filtered = filterHighRisk(cards, highOnly);
    return order === "risk" ? sortCards(filtered) : sortCardsByName(filtered);
  }, [cards, order, highOnly]);

  if (loading) return <EquipmentHomeSkeleton />;

  const refetchAll = () => { today.refetch(); refetchCards(); };
  const selectKpi = (key: KpiKey) => {
    if (key === "highRisk") {
      // 고위험 설비는 오늘 할 일이 아니라 설비 목록을 좁힌다
      if (!highOnly) document.getElementById(GRID_ID)?.scrollIntoView?.({ behavior: "smooth", block: "start" });
      setHighOnly(!highOnly);
      return;
    }
    setFilter((cur) => (cur === key ? null : key));
  };
  const active: KpiKey[] = [...(filter ? [filter] : []), ...(highOnly ? (["highRisk"] as KpiKey[]) : [])];
  const filterLabel = filter ? kpis.find((k) => k.key === filter)?.label ?? null : null;
  // 조회가 실패하면 숫자와 목록을 그리지 않는다. "설비 0대"처럼 사실과 다른 숫자를 보이지 않는다
  const failed = loadError || today.error;

  return (
    <PageLayout>
      <PageHeader title="설비 현황" />
      {failed && <LoadErrorCallout className="mb-4" onRetry={refetchAll} />}
      {!today.error && today.view && <KpiStrip kpis={kpis} active={active} onSelect={selectKpi} />}
      {!today.error && (
        <TodayInbox view={today.view} rows={filterRows(rows, filter)} filterLabel={filterLabel}
          onClearFilter={() => setFilter(null)} onRefresh={refetchAll} />
      )}

      {!loadError && (
        <section id={GRID_ID} aria-label="설비" className="scroll-mt-6">
          <div className="mb-3 flex items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <SectionTitle>설비 {shown.length}대</SectionTitle>
              {highOnly && (
                <button type="button" onClick={() => setHighOnly(false)}
                  className="inline-flex items-center gap-1 rounded border border-brand-line bg-brand-soft px-1.5 py-0.5 text-xs font-bold text-brand hover:bg-white">
                  고위험 설비
                  <X className="h-3 w-3" aria-label="필터 해제" />
                </button>
              )}
            </div>
            <Select aria-label="정렬" className="w-32" value={order} onChange={(e) => setOrder(e.target.value as CardOrder)}>
              <option value="risk">위험도순</option>
              <option value="name">이름순</option>
            </Select>
          </div>
          {shown.length === 0 && <EmptyState message={highOnly ? "고위험 설비 없음" : "등록된 설비 없음"} />}
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {shown.map((card, i) => (
              <EquipmentCard key={card.id} card={card} index={i} />
            ))}
          </div>
        </section>
      )}
    </PageLayout>
  );
}
