import { useEffect, useRef } from "react";

import AgentMessage from "@/components/common/AgentMessage";
import EvidenceGrid from "@/components/evidence/EvidenceGrid";
import WorkPlanResultCard from "@/pages/WorkPlan/components/WorkPlanResultCard";
import type { Turn } from "@/pages/WorkPlan/hooks/useAgentStream";

interface Props {
  turns: Turn[];
  streaming: boolean;
  knownNos?: Set<number>;
  /** 지금 진행 중인 단계 이름. 기다리는 동안 무엇을 하는지 보인다 */
  activity?: string | null;
  onOpenDetail: (id: number) => void;
}

/** 답하는 쪽 표식. 이름 대신 작은 마크만 둔다 */
function Avatar() {
  return (
    <span aria-hidden className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-md border border-slate-200 bg-white">
      <img src="/brand/saife-mark.svg" alt="" className="h-4 w-4" />
    </span>
  );
}

/** 대화 스레드. 페이지가 함께 스크롤되므로 결과 카드가 잘리지 않는다. 새 답이 오면 그 자리로 따라간다. */
export default function ChatThread({ turns, streaming, knownNos, activity, onOpenDetail }: Props) {
  const endRef = useRef<HTMLDivElement>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const last = turns[turns.length - 1];
  const hasCard = !!last?.workPlan;

  useEffect(() => {
    // 결과 카드가 오면 카드 머리가 보이게, 그 밖에는 마지막 줄이 보이게
    const target = hasCard ? cardRef.current : endRef.current;
    target?.scrollIntoView?.({ behavior: "smooth", block: hasCard ? "start" : "end" });
  }, [turns.length, hasCard, activity]);

  const waiting = streaming && last?.role === "user";
  return (
    <div aria-live="polite" aria-busy={streaming} aria-label="대화 내용" className="flex flex-col gap-5">
      {turns.map((turn, i) => turn.role === "user" ? (
        <div key={`${i}-${turn.role}`} className="flex justify-end animate-rise-in">
          <p className="max-w-[78%] rounded-xl rounded-br-sm bg-brand-ink px-4 py-2.5 text-stage text-white">{turn.text}</p>
        </div>
      ) : (
        <div key={`${i}-${turn.role}`} className="flex gap-3 animate-rise-in">
          <Avatar />
          <div className="min-w-0 flex-1 space-y-3">
            {turn.text && (
              <div className="max-w-[92%] rounded-xl rounded-tl-sm border border-slate-200 bg-white px-4 py-3 text-stage text-slate-800 shadow-card">
                <AgentMessage text={turn.text} knownNos={knownNos} scope="chat" />
                {!turn.workPlan && <EvidenceGrid items={turn.evidence} collapsedByDefault scope="chat" />}
              </div>
            )}
            {turn.workPlan && (
              <div ref={i === turns.length - 1 ? cardRef : undefined} className="scroll-mt-6">
                <WorkPlanResultCard detail={turn.workPlan} evidence={turn.evidence} onOpenDetail={onOpenDetail} />
                <EvidenceGrid items={turn.evidence} collapsedByDefault scope="chat" className="mt-3" />
              </div>
            )}
          </div>
        </div>
      ))}
      {waiting && (
        <div className="flex gap-3 animate-fade-in">
          <Avatar />
          <div className="inline-flex items-center gap-2.5 rounded-xl border border-slate-200 bg-white px-4 py-3 shadow-card">
            <span aria-hidden className="relative flex h-2.5 w-2.5">
              <span className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />
              <span className="relative h-2.5 w-2.5 rounded-full bg-brand" />
            </span>
            <span className="text-stage text-slate-600">{activity ?? "확인 중"}</span>
          </div>
        </div>
      )}
      <div ref={endRef} />
    </div>
  );
}
