import { useCallback, useEffect, useState } from "react";
import { useAgentStream } from "@/hooks/useAgentStream";
import { ToolTracePanel } from "@/components/ToolTracePanel";
import { AgentMessage } from "@/components/AgentMessage";
import { formUrl, workPlanApi } from "@/api/saifeApi";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { WorkPlanDetail, WorkPlanListItem } from "@/types/workPlan";

/**
 * UC3 — 대화형 작업계획서 등록.
 *
 * 좌측 대화 / 우측 트레이스 패널이 시연 레이아웃이다. 심사위원이
 * "입력 → 판단 → 도구 실행 → 결과"를 추론하지 않고 눈으로 보게 만든다.
 *
 * 되묻기는 특별한 흐름이 아니라 평범한 멀티턴이다. 모델이 질문하며 턴을 끝내는
 * 것 자체가 일시정지다 — 스트림 중단도, 별도 재개 API도 없다.
 */
export function WorkPlanChatPage() {
  const [input, setInput] = useState("");
  const [slotValue, setSlotValue] = useState("");
  const [plans, setPlans] = useState<WorkPlanListItem[]>([]);
  const [detail, setDetail] = useState<WorkPlanDetail | null>(null);
  const [busy, setBusy] = useState(false);

  const { trace, answer, pendingSlot, error, streaming, restoring, send, answerSlot, reset } =
    useAgentStream();

  const refresh = useCallback(async () => {
    const page = await workPlanApi.list(0, 10);
    setPlans(page.content);
  }, []);

  // 마운트 시 1회. 이후 갱신은 턴이 끝난 뒤 핸들러가 직접 부른다.
  // 낙관적 플래그로 "생성됐다"고 가정하지 않는다 — 서버 데이터에서만 파생한다
  useEffect(() => {
    void workPlanApi.list(0, 10).then((page) => setPlans(page.content));
  }, []);

  /** 한 턴을 보내고, 끝나면 목록을 다시 읽는다 */
  const sendTurn = useCallback(
    async (message: string, slotKey?: string) => {
      if (slotKey) {
        await answerSlot(slotKey, message);
      } else {
        await send(message);
      }
      await refresh();
    },
    [answerSlot, send, refresh],
  );

  const openDetail = async (id: number) => {
    setDetail(await workPlanApi.detail(id));
  };

  const acknowledge = async (id: number) => {
    setBusy(true);
    try {
      setDetail(await workPlanApi.acknowledge(id));
      await refresh();
    } finally {
      setBusy(false);
    }
  };

  const approve = async (id: number) => {
    setBusy(true);
    try {
      setDetail(await workPlanApi.approve(id, "관리부"));
      await refresh();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="grid h-full grid-cols-[1fr_380px] overflow-hidden">
      <section className="flex flex-col overflow-hidden p-6">
        <header className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-semibold">작업계획서 대화형 등록</h1>
            <p className="text-sm text-slate-500">
              작업 내용을 말로 설명하면 서식을 채우고, 모르는 값만 되묻습니다.
            </p>
          </div>
          <button
            className="rounded border px-3 py-1.5 text-sm hover:bg-slate-50"
            onClick={() => {
              reset();
              setDetail(null);
            }}
          >
            새 대화
          </button>
        </header>

        <div className="mt-4 flex-1 overflow-auto rounded border bg-white p-4">
          {answer ? (
            <AgentMessage text={answer} />
          ) : (
            <span className="text-slate-400">
              {restoring
                ? "이전 대화를 불러오는 중…"
                : "예: 내일 공장동 후면 차양부에서 사다리 놓고 천장 페인트 칠할 건데요"}
            </span>
          )}
        </div>

        {pendingSlot && (
          <div className="mt-3 rounded border border-amber-300 bg-amber-50 p-4">
            <p className="font-medium">{pendingSlot.question}</p>
            {pendingSlot.ledgerValue && (
              <p className="mt-1 text-sm text-slate-600">
                설비 대장 기록: {pendingSlot.ledgerValue}
              </p>
            )}
            <div className="mt-2 flex gap-2">
              <input
                className="flex-1 rounded border px-3 py-2"
                value={slotValue}
                onChange={(e) => setSlotValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !streaming) {
                    void sendTurn(slotValue, pendingSlot.slotKey);
                    setSlotValue("");
                  }
                }}
              />
              <button
                className="rounded bg-slate-900 px-4 py-2 text-white"
                onClick={() => {
                  void sendTurn(slotValue, pendingSlot.slotKey);
                  setSlotValue("");
                }}
              >
                답변
              </button>
            </div>
          </div>
        )}

        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

        <div className="mt-3 flex gap-2">
          <input
            className="flex-1 rounded border px-3 py-2"
            placeholder="작업 내용을 입력하세요"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !streaming && input.trim()) {
                void sendTurn(input);
                setInput("");
              }
            }}
          />
          <button
            className="rounded bg-slate-900 px-5 py-2 text-white disabled:opacity-40"
            disabled={streaming || !input.trim()}
            onClick={() => {
              void sendTurn(input);
              setInput("");
            }}
          >
            {streaming ? "처리 중…" : "보내기"}
          </button>
        </div>

        <section className="mt-5">
          <h2 className="text-sm font-semibold text-slate-600">작업계획서 목록</h2>
          <div className="mt-2 max-h-52 overflow-auto rounded border bg-white">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-left text-slate-500">
                <tr>
                  <th className="px-3 py-2">작업명</th>
                  <th className="px-3 py-2">일자</th>
                  <th className="px-3 py-2">설비</th>
                  <th className="px-3 py-2">상태</th>
                  <th className="px-3 py-2">브리핑</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody>
                {plans.length === 0 && (
                  <tr>
                    <td className="px-3 py-4 text-slate-400" colSpan={6}>
                      등록된 작업계획서가 없습니다.
                    </td>
                  </tr>
                )}
                {plans.map((p) => (
                  <tr key={p.id} className="border-t">
                    <td className="px-3 py-2">{p.workName}</td>
                    <td className="px-3 py-2 tabular-nums">{p.workDate}</td>
                    <td className="px-3 py-2">{p.equipmentName ?? "-"}</td>
                    <td className="px-3 py-2">{WORK_PLAN_STATUS_LABEL[p.status]}</td>
                    <td className="px-3 py-2">
                      {p.briefingAcknowledged ? (
                        <span className="text-emerald-700">확인됨</span>
                      ) : (
                        <span className="text-slate-400">미확인</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right">
                      <button
                        className="rounded border px-2 py-1 text-xs hover:bg-slate-50"
                        onClick={() => void openDetail(p.id)}
                      >
                        열기
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        {detail && (
          <section className="mt-4 rounded border bg-white p-4">
            <div className="flex items-center justify-between">
              <h2 className="font-semibold">
                #{detail.id} {detail.workName}
              </h2>
              <div className="flex gap-2">
                <a
                  className="rounded border px-3 py-1.5 text-sm hover:bg-slate-50"
                  href={formUrl.workPlan(detail.id)}
                  target="_blank"
                  rel="noreferrer"
                >
                  법정 서식
                </a>
                {!detail.briefingAckAt && detail.briefing && (
                  <button
                    className="rounded bg-amber-500 px-3 py-1.5 text-sm text-white disabled:opacity-40"
                    disabled={busy}
                    onClick={() => void acknowledge(detail.id)}
                  >
                    브리핑 확인 (TBM 기록)
                  </button>
                )}
                {detail.status === "SUBMITTED" && (
                  <button
                    className="rounded bg-slate-900 px-3 py-1.5 text-sm text-white disabled:opacity-40"
                    disabled={busy}
                    onClick={() => void approve(detail.id)}
                  >
                    승인
                  </button>
                )}
              </div>
            </div>

            {detail.briefingAckAt && (
              <p className="mt-1 text-sm text-emerald-700">
                브리핑 확인 {new Date(detail.briefingAckAt).toLocaleString("ko-KR")} — 상시평가
                트랙의 TBM 증빙으로 보존됩니다.
              </p>
            )}

            {detail.slots.length > 0 && (
              <table className="mt-3 w-full text-sm">
                <thead className="text-left text-slate-500">
                  <tr>
                    <th className="py-1">확인 항목</th>
                    <th className="py-1 w-28">대장 기록</th>
                    <th className="py-1 w-28">작업자 확인</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.slots.map((s) => (
                    <tr key={s.slotKey} className="border-t">
                      <td className="py-1.5">{s.question}</td>
                      <td className="py-1.5">{s.ledgerValue ?? "-"}</td>
                      <td className={s.conflicted ? "py-1.5 text-red-700" : "py-1.5"}>
                        {s.answeredValue ?? "-"}
                        {s.conflicted && " (불일치)"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {detail.briefing && (
              <pre className="mt-3 max-h-64 overflow-auto whitespace-pre-wrap rounded bg-slate-50 p-3 text-sm">
                {detail.briefing}
              </pre>
            )}
          </section>
        )}
      </section>

      <ToolTracePanel rows={trace} />
    </div>
  );
}
