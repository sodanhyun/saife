// WorkPlanPage.tsx — UC3 대화형 작업계획서. 좌 대화 / 우 트레이스가 시연 레이아웃이다.
import cn from "@/lib/cn";
import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import ChatThread from "@/pages/WorkPlan/components/ChatThread";
import Composer from "@/pages/WorkPlan/components/Composer";
import SlotPrompt from "@/pages/WorkPlan/components/SlotPrompt";
import ToolTracePanel from "@/pages/WorkPlan/components/ToolTracePanel";
import WorkPlanDetailModal from "@/pages/WorkPlan/components/WorkPlanDetailModal";
import WorkPlanTable from "@/pages/WorkPlan/components/WorkPlanTable";
import { useAgentStream } from "@/pages/WorkPlan/hooks/useAgentStream";
import { useWorkPlans } from "@/pages/WorkPlan/hooks/useWorkPlans";
import WorkPlanSkeleton from "@/pages/WorkPlan/WorkPlanSkeleton";

export default function WorkPlanPage() {
  const agent = useAgentStream();
  const plans = useWorkPlans();

  if (plans.loading && plans.plans.length === 0) return <WorkPlanSkeleton />;

  const sendTurn = async (message: string, slotKey?: string) => {
    if (slotKey) await agent.answerSlot(slotKey, message); else await agent.send(message);
    plans.refetch(); // 턴이 끝난 뒤 서버 데이터로 목록 갱신
  };

  return (
    <PageLayout>
      <PageHeader title="작업계획서 대화형 등록" description="작업 내용을 말로 설명하면 서식을 채우고, 모르는 값만 되묻습니다."
        actions={<Button variant="secondary" size="sm" onClick={() => { agent.reset(); plans.closeDetail(); }}>새 대화</Button>} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
        {/* min-h-0 — 그리드 아이템 기본값(min-height: auto)을 눌러야 ChatThread의 max-h가
            실제로 상한으로 동작한다(안 그러면 섹션이 내용만큼 늘어나 스크롤이 죽는다).
            cn()으로 병합해 min-h-[520px]와의 충돌을 twMerge가 뒤엣것 우선으로 정리하게 한다. */}
        <section className={cn("flex min-h-[520px] flex-col gap-3", "min-h-0")}>
          <ChatThread turns={agent.turns} streaming={agent.streaming} restoring={agent.restoring} />
          {agent.pendingSlot && <SlotPrompt key={agent.pendingSlot.slotKey} slot={agent.pendingSlot} disabled={agent.streaming} onAnswer={(k, v) => void sendTurn(v, k)} />}
          {agent.error && <Callout tone="high">{agent.error}</Callout>}
          <Composer disabled={agent.streaming} onSend={(m) => void sendTurn(m)} />
          {plans.loadError && <Callout tone="high">데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.</Callout>}
          <SectionTitle className="mt-2">작업계획서 목록</SectionTitle>
          <WorkPlanTable plans={plans.plans} onOpen={plans.openDetail} />
        </section>
        <ToolTracePanel rows={agent.trace} connectionState={agent.connectionState} />
      </div>
      <WorkPlanDetailModal detail={plans.detail} busyAction={plans.busyAction} onClose={plans.closeDetail} onAcknowledge={plans.acknowledge} onApprove={plans.approve} />
    </PageLayout>
  );
}
