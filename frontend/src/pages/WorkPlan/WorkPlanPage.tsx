// WorkPlanPage.tsx — 작업 전 점검. 대화로 「작업 전 안전점검표」를 채운다.
// 대화 전에는 입력창이 전체 폭이고, 대화가 시작되면 오른쪽에 진행 패널이 열린다.
import { useEffect, useRef } from "react";

import { useSearchParams } from "react-router-dom";

import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";
import AgentFlowPanel from "@/pages/WorkPlan/components/AgentFlowPanel";
import ChatThread from "@/pages/WorkPlan/components/ChatThread";
import Composer from "@/pages/WorkPlan/components/Composer";
import RecallCard from "@/pages/WorkPlan/components/RecallCard";
import WorkPlanDetailModal from "@/pages/WorkPlan/components/WorkPlanDetailModal";
import WorkPlanRecords from "@/pages/WorkPlan/components/WorkPlanRecords";
import { useAgentStream } from "@/pages/WorkPlan/hooks/useAgentStream";
import { useEntryEquipment } from "@/pages/WorkPlan/hooks/useEntryEquipment";
import { useWorkPlans } from "@/pages/WorkPlan/hooks/useWorkPlans";
import { stageTitle } from "@/pages/WorkPlan/utils/flowStages";
import WorkPlanSkeleton from "@/pages/WorkPlan/WorkPlanSkeleton";

export default function WorkPlanPage() {
  // entry가 먼저다. useAgentStream이 마운트 첫 렌더에서 진입 설비 ID로 "저장된 대화를
  // 이어갈지, 버리고 새로 시작할지"를 결정한다(F4).
  const entry = useEntryEquipment();
  const agent = useAgentStream(entry.equipmentId);
  const plans = useWorkPlans();

  // 홈 "검토"에서 ?planId=로 들어오면 그 점검표의 검토 화면을 바로 연다(한 번만)
  const [searchParams] = useSearchParams();
  const openedPlanRef = useRef(false);
  const openDetail = plans.openDetail;
  useEffect(() => {
    const raw = searchParams.get("planId");
    if (openedPlanRef.current || !raw || !Number.isFinite(Number(raw))) return;
    openedPlanRef.current = true;
    openDetail(Number(raw));
  }, [searchParams, openDetail]);

  if (plans.loading && plans.plans.length === 0) return <WorkPlanSkeleton />;

  const sendTurn = async (message: string, slotKey?: string) => {
    if (slotKey) await agent.answerSlot(slotKey, message);
    else await agent.send(message, undefined, entry.equipmentId ?? undefined);
    plans.refetch(); // 턴이 끝난 뒤 서버 데이터로 목록 갱신
  };

  // 설비 기록은 한 장뿐이다. 대화 중 ai.recall이 오면 그쪽이 진입 회상을 대체한다.
  const activeRecall = agent.recall ?? entry.recall;
  const started = agent.turns.length > 0;

  // 기다리는 동안 무엇을 하는지: 진행 중인 단계 이름
  const running = agent.trace.find((t) => t.status === "running");
  const activity = running ? `${stageTitle(running.toolName)} 중` : null;

  const conversation = (
    <section className="flex min-w-0 flex-col gap-4">
      {activeRecall && <RecallCard recall={activeRecall} collapsed={started} />}
      {agent.restoring && !started && <Skeleton className="h-24 w-full" />}
      {started && (
        <ChatThread turns={agent.turns} streaming={agent.streaming} knownNos={agent.knownNos}
          activity={activity} onOpenDetail={plans.openDetail} />
      )}
      {agent.error && <Callout tone="high">{agent.error}</Callout>}
      <Composer disabled={agent.streaming} slot={agent.streaming ? null : agent.pendingSlot}
        showExamples={!started && !agent.restoring} onSend={(m, k) => void sendTurn(m, k)} />
    </section>
  );

  return (
    <PageLayout>
      <PageHeader title="작업 전 점검"
        actions={started ? <Button variant="secondary" size="sm" onClick={() => { agent.reset(); plans.closeDetail(); }}>새 대화</Button> : undefined} />
      {started ? (
        <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
          {conversation}
          <div className="lg:sticky lg:top-6">
            <AgentFlowPanel rows={agent.trace} />
          </div>
        </div>
      ) : (
        conversation
      )}
      <WorkPlanRecords plans={plans.plans} total={plans.total} hasMore={plans.hasMore} keyword={plans.keyword} status={plans.status}
        loadError={plans.loadError} onKeyword={plans.setKeyword} onStatus={plans.setStatus} onMore={plans.loadMore}
        onOpen={plans.openDetail} onRetry={plans.refetch} />
      <WorkPlanDetailModal detail={plans.detail} busyAction={plans.busyAction} onClose={plans.closeDetail}
        onApprove={plans.approve} onHold={plans.hold} />
    </PageLayout>
  );
}
