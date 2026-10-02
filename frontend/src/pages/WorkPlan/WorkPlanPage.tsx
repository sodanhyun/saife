// WorkPlanPage.tsx — UC3 대화형 작업계획서. 좌 대화 / 우 에이전트 작업 흐름이 시연 레이아웃이다.
import { useEffect, useRef } from "react";

import { useSearchParams } from "react-router-dom";

import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import LinkButton from "@/components/ui/LinkButton";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import AgentFlowPanel from "@/pages/WorkPlan/components/AgentFlowPanel";
import ChatThread from "@/pages/WorkPlan/components/ChatThread";
import Composer from "@/pages/WorkPlan/components/Composer";
import RecallCard from "@/pages/WorkPlan/components/RecallCard";
import SlotPrompt from "@/pages/WorkPlan/components/SlotPrompt";
import WorkPlanDetailModal from "@/pages/WorkPlan/components/WorkPlanDetailModal";
import WorkPlanTable from "@/pages/WorkPlan/components/WorkPlanTable";
import { useAgentStream } from "@/pages/WorkPlan/hooks/useAgentStream";
import { useEntryEquipment } from "@/pages/WorkPlan/hooks/useEntryEquipment";
import { useWorkPlans } from "@/pages/WorkPlan/hooks/useWorkPlans";
import { FLOW_STAGES } from "@/pages/WorkPlan/utils/flowStages";
import WorkPlanSkeleton from "@/pages/WorkPlan/WorkPlanSkeleton";

export default function WorkPlanPage() {
  // entry가 먼저다. useAgentStream이 마운트 첫 렌더에서 진입 설비 ID로 "저장된 대화를
  // 이어갈지, 버리고 새로 시작할지"를 결정한다(F4).
  const entry = useEntryEquipment();
  const agent = useAgentStream(entry.equipmentId);
  const plans = useWorkPlans();

  // 홈 "승인 검토"에서 ?planId=로 들어오면 그 계획서의 승인 화면을 바로 연다(한 번만)
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

  // 회상 카드는 한 장뿐이다. 대화 중 ai.recall이 오면 그쪽이 진입 회상을 통째로 대체한다.
  const activeRecall = agent.recall ?? entry.recall;

  // 기다리는 동안 무엇을 하는지: 실행 중인 단계 이름
  const running = agent.trace.find((t) => t.status === "running");
  const activity = running ? `${FLOW_STAGES.find((s) => s.tool === running.toolName)?.title ?? running.toolName} 중` : null;

  // 돌아오기 ①: 대화가 끝났고 계획서가 제출됐으면 설비 타임라인 링크를 둔다. 자동 이동은 하지 않는다.
  const created = agent.trace.some((t) => t.toolName === "createWorkPlan" && t.status === "ok");
  const showReturnLink = !agent.streaming && created && entry.equipmentId !== null;

  return (
    <PageLayout>
      <PageHeader eyebrow="작업 신고" title="작업계획서 대화형 등록"
        description="작업을 평소 말투로 적으면 서식을 채우고, 설비 대장이 모르는 값만 되묻습니다."
        actions={<Button variant="secondary" size="sm" onClick={() => { agent.reset(); plans.closeDetail(); }}>새 대화</Button>} />
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_380px]">
        <section className="flex min-w-0 flex-col gap-4">
          {activeRecall && <RecallCard recall={activeRecall} />}
          {!activeRecall && entry.equipmentId !== null && entry.equipmentName && (
            <p className="text-sm text-slate-600">대상 설비: <span className="font-semibold text-slate-900">{entry.equipmentName}</span>{entry.locationTag ? `, ${entry.locationTag}` : ""}</p>
          )}
          <ChatThread turns={agent.turns} streaming={agent.streaming} restoring={agent.restoring} knownNos={agent.knownNos}
            activity={activity} onOpenDetail={plans.openDetail} />
          {agent.pendingSlot && <SlotPrompt key={agent.pendingSlot.slotKey} slot={agent.pendingSlot} disabled={agent.streaming} onAnswer={(k, v) => void sendTurn(v, k)} />}
          {agent.error && <Callout tone="high">{agent.error}</Callout>}
          {showReturnLink && (
            <LinkButton href={`/equipment/${entry.equipmentId}`} className="self-start">이 설비 타임라인에 기록됨, 보기</LinkButton>
          )}
          <Composer disabled={agent.streaming} onSend={(m) => void sendTurn(m)} />
          {plans.loadError && <Callout tone="high">데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.</Callout>}
          <SectionTitle className="mt-4">작업계획서 목록</SectionTitle>
          <WorkPlanTable plans={plans.plans} onOpen={plans.openDetail} />
        </section>
        <div className="lg:sticky lg:top-6">
          <AgentFlowPanel rows={agent.trace} connectionState={agent.connectionState} streaming={agent.streaming} evidence={agent.evidenceCount} />
        </div>
      </div>
      <WorkPlanDetailModal detail={plans.detail} busyAction={plans.busyAction} onClose={plans.closeDetail} onAcknowledge={plans.acknowledge} onApprove={plans.approve} />
    </PageLayout>
  );
}
