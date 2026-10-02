import { useEffect, useRef, useState } from "react";

import AgentMessage from "@/components/common/AgentMessage";
import EvidenceGrid from "@/components/evidence/EvidenceGrid";
import cn from "@/lib/cn";
import WorkPlanResultCard from "@/pages/WorkPlan/components/WorkPlanResultCard";
import type { Turn } from "@/pages/WorkPlan/hooks/useAgentStream";

interface Props {
  turns: Turn[];
  streaming: boolean;
  restoring: boolean;
  knownNos?: Set<number>;
  /** 지금 실행 중인 단계 이름. 기다리는 동안 무엇을 하는지 보인다 */
  activity?: string | null;
  onOpenDetail: (id: number) => void;
}

/** 결과 카드가 있는 턴의 모델 문장. 카드와 같은 내용이라 접어서 시작한다 */
function Explanation({ turn, knownNos }: { turn: Turn; knownNos?: Set<number> }) {
  const [open, setOpen] = useState(false);
  if (!turn.text && turn.evidence.length === 0) return null;
  return (
    <div className="mt-2">
      <button type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open}
        className="text-xs font-semibold text-slate-500 hover:text-slate-800">
        {open ? "에이전트 설명 접기" : `에이전트 설명과 근거 ${turn.evidence.length}건 보기`}
      </button>
      {open && (
        <div className="mt-2 rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm">
          <AgentMessage text={turn.text} knownNos={knownNos} scope="chat" />
          <EvidenceGrid items={turn.evidence} collapsedByDefault scope="chat" />
        </div>
      )}
    </div>
  );
}

/** 대화 스레드. 새 답변이 오면 아래로 따라간다(발표자가 이전 턴을 보고 있게 되는 것을 막는다). */
export default function ChatThread({ turns, streaming, restoring, knownNos, activity, onOpenDetail }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { const el = ref.current; if (el) el.scrollTop = el.scrollHeight; }, [turns, activity]);
  const waiting = streaming && turns[turns.length - 1]?.role === "user";
  return (
    <div ref={ref} aria-live="polite" aria-busy={streaming} aria-label="대화 내용"
      className="min-h-[420px] flex-1 space-y-5 overflow-auto rounded-xl border border-slate-200 bg-white p-5 shadow-card lg:max-h-[calc(100vh-300px)]">
      {turns.length === 0 && (
        <div className="py-10 text-center">
          <p className="text-stage font-semibold text-slate-700">{restoring ? "이전 대화를 불러오는 중입니다" : "오늘 할 작업을 평소 말투로 적어 주세요"}</p>
          {!restoring && <p className="mt-1 text-sm text-slate-400">예: 내일 공장동 후면 차양부에서 사다리 놓고 천장 페인트 칠할 건데요</p>}
        </div>
      )}
      {turns.map((turn, i) => turn.role === "user" ? (
        <div key={`${i}-${turn.role}`} className="flex justify-end animate-rise-in">
          <p className="max-w-[78%] rounded-xl rounded-br-sm bg-brand-ink px-4 py-2.5 text-stage text-white">{turn.text}</p>
        </div>
      ) : (
        <div key={`${i}-${turn.role}`} className="animate-rise-in">
          <p className="mb-1.5 text-xs font-bold tracking-wide text-brand">SAIFE 에이전트</p>
          {turn.workPlan ? (
            <>
              <WorkPlanResultCard detail={turn.workPlan} evidence={turn.evidence} onOpenDetail={onOpenDetail} />
              <Explanation turn={turn} knownNos={knownNos} />
            </>
          ) : (
            <div className={cn("max-w-[92%] rounded-xl rounded-tl-sm border border-slate-200 bg-slate-50 px-4 py-3 text-stage text-slate-800")}>
              <AgentMessage text={turn.text} knownNos={knownNos} scope="chat" />
              <EvidenceGrid items={turn.evidence} collapsedByDefault scope="chat" />
            </div>
          )}
        </div>
      ))}
      {waiting && (
        <div className="animate-fade-in">
          <p className="mb-1.5 text-xs font-bold tracking-wide text-brand">SAIFE 에이전트</p>
          <div className="inline-flex items-center gap-2.5 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3">
            <span aria-hidden className="relative flex h-2.5 w-2.5">
              <span className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />
              <span className="relative h-2.5 w-2.5 rounded-full bg-brand" />
            </span>
            <span className="text-stage text-slate-600">{activity ?? "요청을 이해하고 다음 단계를 고르는 중"}</span>
          </div>
        </div>
      )}
    </div>
  );
}
