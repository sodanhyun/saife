// WorkPlanPage.tsx — UC3 대화형 작업계획서. 좌 대화 / 우 트레이스가 시연 레이아웃이다.
import cn from "@/lib/cn";
import { Badge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import LinkButton from "@/components/ui/LinkButton";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import ChatThread from "@/pages/WorkPlan/components/ChatThread";
import Composer from "@/pages/WorkPlan/components/Composer";
import RecallCard from "@/pages/WorkPlan/components/RecallCard";
import SlotPrompt from "@/pages/WorkPlan/components/SlotPrompt";
import ToolTracePanel from "@/pages/WorkPlan/components/ToolTracePanel";
import WorkPlanDetailModal from "@/pages/WorkPlan/components/WorkPlanDetailModal";
import WorkPlanTable from "@/pages/WorkPlan/components/WorkPlanTable";
import { useAgentStream } from "@/pages/WorkPlan/hooks/useAgentStream";
import { useEntryEquipment } from "@/pages/WorkPlan/hooks/useEntryEquipment";
import { useWorkPlans } from "@/pages/WorkPlan/hooks/useWorkPlans";
import WorkPlanSkeleton from "@/pages/WorkPlan/WorkPlanSkeleton";

export default function WorkPlanPage() {
  // entry가 먼저다 — useAgentStream이 마운트 첫 렌더에서 진입 설비 ID로 "저장된 대화를
  // 이어갈지, 버리고 새로 시작할지"를 결정한다(F4).
  const entry = useEntryEquipment();
  const agent = useAgentStream(entry.equipmentId);
  const plans = useWorkPlans();

  if (plans.loading && plans.plans.length === 0) return <WorkPlanSkeleton />;

  const sendTurn = async (message: string, slotKey?: string) => {
    if (slotKey) await agent.answerSlot(slotKey, message);
    else await agent.send(message, undefined, entry.equipmentId ?? undefined);
    plans.refetch(); // 턴이 끝난 뒤 서버 데이터로 목록 갱신
  };

  // 회상 카드는 한 장뿐이다 — 대화 중 ai.recall이 오면 그쪽이 진입 회상을 통째로 대체한다.
  // 새 대화(reset)로 agent.recall이 비면 ?equipmentId=가 있는 한 진입 회상이 그대로 다시 보인다.
  const activeRecall = agent.recall ?? entry.recall;

  // 돌아오기 ① — 대화가 끝났고(ai.done, streaming=false) 마지막 턴의 트레이스에
  // createWorkPlan이 성공(ok)했으면. 자동 이동은 하지 않는다 — 링크만 둔다.
  const lastTurnCreatedWorkPlan = agent.trace.some((t) => t.toolName === "createWorkPlan" && t.status === "ok");
  const showReturnLink = !agent.streaming && agent.turns.length > 0 && lastTurnCreatedWorkPlan && entry.equipmentId !== null;

  return (
    <PageLayout>
      <PageHeader title="작업계획서 대화형 등록" description="작업 내용을 말로 설명하면 서식을 채우고, 모르는 값만 되묻습니다."
        actions={<Button variant="secondary" size="sm" onClick={() => { agent.reset(); plans.closeDetail(); }}>새 대화</Button>} />
      {/* 회상 카드가 뜨면 설비 배지는 숨긴다 — 같은 정보(설비·위치)를 카드가 이미 담고 있다 */}
      {activeRecall ? (
        <RecallCard recall={activeRecall} className="mb-3" />
      ) : (
        entry.equipmentId !== null && entry.equipmentName && (
          <Badge className="mb-3">
            설비: {entry.equipmentName}{entry.locationTag ? ` · ${entry.locationTag}` : ""}
          </Badge>
        )
      )}
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
        {/* min-h-0 — 그리드 아이템 기본값(min-height: auto)을 눌러야 ChatThread의 max-h가
            실제로 상한으로 동작한다(안 그러면 섹션이 내용만큼 늘어나 스크롤이 죽는다).
            cn()으로 병합해 min-h-[520px]와의 충돌을 twMerge가 뒤엣것 우선으로 정리하게 한다. */}
        <section className={cn("flex min-h-[520px] flex-col gap-3", "min-h-0")}>
          <ChatThread turns={agent.turns} streaming={agent.streaming} restoring={agent.restoring} knownNos={agent.knownNos} />
          {agent.pendingSlot && <SlotPrompt key={agent.pendingSlot.slotKey} slot={agent.pendingSlot} disabled={agent.streaming} onAnswer={(k, v) => void sendTurn(v, k)} />}
          {agent.error && <Callout tone="high">{agent.error}</Callout>}
          {showReturnLink && (
            <LinkButton href={`/equipment/${entry.equipmentId}`} className="self-start">
              이 설비 타임라인에 기록됨 → 보기
            </LinkButton>
          )}
          <Composer disabled={agent.streaming} onSend={(m) => void sendTurn(m)} />
          {plans.loadError && <Callout tone="high">데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.</Callout>}
          <SectionTitle className="mt-2">작업계획서 목록</SectionTitle>
          <WorkPlanTable plans={plans.plans} onOpen={plans.openDetail} />
        </section>
        <div className="flex flex-col gap-2">
          <ToolTracePanel rows={agent.trace} connectionState={agent.connectionState} />
          {agent.evidenceCount.total > 0 && (
            <p className="text-xs text-slate-500">
              이 대화의 근거 {agent.evidenceCount.total}건 · 사진 {agent.evidenceCount.photos} · 지침 {agent.evidenceCount.guides} · 조문 {agent.evidenceCount.laws} · MSDS {agent.evidenceCount.msds}
            </p>
          )}
        </div>
      </div>
      <WorkPlanDetailModal detail={plans.detail} busyAction={plans.busyAction} onClose={plans.closeDetail} onAcknowledge={plans.acknowledge} onApprove={plans.approve} />
    </PageLayout>
  );
}
