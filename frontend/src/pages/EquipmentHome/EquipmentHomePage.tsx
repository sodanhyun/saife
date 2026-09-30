// EquipmentHomePage.tsx — IA 뒤집기. 명사(설비) 하나 위에 동사 네 개가 얹힌다.
import Callout from "@/components/ui/Callout";
import EmptyState from "@/components/ui/EmptyState";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import EquipmentCard from "@/pages/EquipmentHome/components/EquipmentCard";
import TodayInbox from "@/pages/EquipmentHome/components/TodayInbox";
import { useEquipmentCards } from "@/pages/EquipmentHome/hooks/useEquipmentCards";
import EquipmentHomeSkeleton from "@/pages/EquipmentHome/EquipmentHomeSkeleton";

export default function EquipmentHomePage() {
  const { cards, loading, loadError } = useEquipmentCards();
  if (loading) return <EquipmentHomeSkeleton />;
  return (
    <PageLayout>
      <PageHeader
        title="설비 현황"
        description="모든 기록은 설비 위에 쌓입니다. 설비를 고르면 그 설비가 기억하는 것부터 보입니다."
      />
      <TodayInbox />
      {loadError && (
        <Callout tone="high" className="mb-4">
          데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.
        </Callout>
      )}
      {!loadError && cards.length === 0 && <EmptyState message="등록된 설비가 없습니다" />}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {cards.map((card) => (
          <EquipmentCard key={card.id} card={card} />
        ))}
      </div>
    </PageLayout>
  );
}
