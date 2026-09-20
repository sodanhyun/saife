import { useEffect, useState } from "react";
import { equipmentApi, formUrl, timelineApi } from "@/api/saifeApi";
import { ACCIDENT_LABEL, RISK_CLASS, RISK_LABEL } from "@/types/domain";
import type { EquipmentItem } from "@/types/equipment";
import type { EquipmentTimeline, TimelineEvent } from "@/types/timeline";
import { EVENT_LABEL } from "@/types/timeline";

/**
 * UC4 — 설비 1개의 타임라인.
 *
 * 이 화면이 증명하는 건 기능이 아니라 <b>구조</b>다. 평가에서 나온 조치가 미이행으로
 * 남고, 그 설비 위에서 작업계획서가 쓰이고, 사고가 나고, 그 사고가 수시평가를 만든다 —
 * 전부 같은 설비 ID에 매달려 있다.
 *
 * 프로젝터에서 읽혀야 한다. 글자를 작게 넣어 정보를 늘리지 않는다.
 */
export function TimelinePage() {
  const [equipment, setEquipment] = useState<EquipmentItem[]>([]);
  const [equipmentId, setEquipmentId] = useState<number | null>(null);
  const [timeline, setTimeline] = useState<EquipmentTimeline | null>(null);
  const [focus, setFocus] = useState<string | null>(null);

  useEffect(() => {
    void equipmentApi.list().then((list) => {
      setEquipment(list);
      setEquipmentId((prev) => prev ?? list[0]?.id ?? null);
    });
  }, []);

  useEffect(() => {
    if (equipmentId === null) return;
    void timelineApi.byEquipment(equipmentId).then(setTimeline);
  }, [equipmentId]);

  /** 포커스된 사건과 연결된 사건들 — 선 대신 강조로 표현한다 */
  const linked = new Set<string>();
  if (focus && timeline) {
    const target = timeline.events.find((e) => e.id === focus);
    target?.linkedEventIds.forEach((id) => linked.add(id));
    timeline.events
      .filter((e) => e.linkedEventIds.includes(focus))
      .forEach((e) => linked.add(e.id));
  }

  return (
    <div className="h-full overflow-auto p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">설비 타임라인</h1>
          <p className="text-sm text-slate-500">
            평가 → 작업계획서 → 사고 → 재평가가 하나의 설비 ID 위에서 이어집니다.
          </p>
        </div>
        <select
          className="rounded border px-3 py-2"
          value={equipmentId ?? ""}
          onChange={(e) => setEquipmentId(Number(e.target.value))}
        >
          {equipment.map((e) => (
            <option key={e.id} value={e.id}>
              {e.name} — {e.locationTag ?? "-"}
            </option>
          ))}
        </select>
      </header>

      {timeline && (
        <>
          <section className="mt-4 rounded border bg-white p-4">
            <div className="flex flex-wrap items-center gap-3">
              <h2 className="text-lg font-semibold">{timeline.equipment.name}</h2>
              <span className="text-sm text-slate-500">
                {timeline.equipment.locationTag} · {timeline.equipment.processName}
              </span>
              {timeline.summary.currentRiskLevel && (
                <span
                  className={`rounded border px-2 py-0.5 text-sm font-semibold ${RISK_CLASS[timeline.summary.currentRiskLevel]}`}
                >
                  현재 위험성 {RISK_LABEL[timeline.summary.currentRiskLevel]}
                </span>
              )}
            </div>

            <p className="mt-2 text-base">{timeline.summary.headline}</p>

            <dl className="mt-3 flex flex-wrap gap-5 text-sm">
              <Stat label="위험성평가" value={timeline.summary.assessmentCount} />
              <Stat label="작업계획서" value={timeline.summary.workPlanCount} />
              <Stat label="사고" value={timeline.summary.incidentCount} danger />
              <Stat
                label="미이행 조치"
                value={timeline.summary.unfinishedActionCount}
                danger={timeline.summary.overdueActionCount > 0}
              />
              <Stat
                label="기한 경과"
                value={timeline.summary.overdueActionCount}
                danger={timeline.summary.overdueActionCount > 0}
              />
            </dl>
          </section>

          <section className="mt-5">
            <ol className="relative border-l-2 border-slate-300 pl-6">
              {timeline.events.map((ev) => (
                <TimelineRow
                  key={ev.id}
                  event={ev}
                  focused={focus === ev.id}
                  linked={linked.has(ev.id)}
                  onFocus={() => setFocus(focus === ev.id ? null : ev.id)}
                />
              ))}
              {timeline.events.length === 0 && (
                <li className="py-6 text-slate-400">이 설비에 기록된 사건이 없습니다.</li>
              )}
            </ol>
          </section>

          <p className="mt-3 text-xs text-slate-500">
            사건을 클릭하면 연결된 기록이 함께 강조됩니다. 연결 관계는 서버가 계산한 것입니다.
          </p>
        </>
      )}
    </div>
  );
}

function Stat({ label, value, danger }: { label: string; value: number; danger?: boolean }) {
  return (
    <div>
      <dt className="text-slate-500">{label}</dt>
      <dd
        className={
          danger && value > 0
            ? "text-xl font-semibold tabular-nums text-red-700"
            : "text-xl font-semibold tabular-nums"
        }
      >
        {value}
      </dd>
    </div>
  );
}

function TimelineRow({
  event,
  focused,
  linked,
  onFocus,
}: {
  event: TimelineEvent;
  focused: boolean;
  linked: boolean;
  onFocus: () => void;
}) {
  const dot =
    event.emphasis === "CRITICAL"
      ? "bg-red-600"
      : event.emphasis === "WARNING"
        ? "bg-amber-500"
        : "bg-slate-400";

  const card = focused
    ? "border-slate-900 ring-2 ring-slate-900"
    : linked
      ? "border-red-400 bg-red-50"
      : "border-slate-200";

  return (
    <li className="relative mb-4">
      <span className={`absolute -left-[31px] top-4 h-3.5 w-3.5 rounded-full ${dot}`} />
      <button
        className={`w-full rounded border bg-white p-4 text-left transition ${card}`}
        onClick={onFocus}
      >
        <div className="flex flex-wrap items-center gap-2">
          <span className="tabular-nums text-sm text-slate-500">{event.at}</span>
          <span className="rounded bg-slate-100 px-2 py-0.5 text-xs">
            {EVENT_LABEL[event.type]}
          </span>
          <span className="font-medium">{event.title}</span>
          {event.riskLevel && (
            <span
              className={`rounded border px-2 py-0.5 text-xs font-semibold ${RISK_CLASS[event.riskLevel]}`}
            >
              {RISK_LABEL[event.riskLevel]}
            </span>
          )}
          {event.accidentType && (
            <span className="text-xs text-slate-500">
              {ACCIDENT_LABEL[event.accidentType]}
            </span>
          )}
        </div>

        <p className="mt-1 text-sm text-slate-700">{event.detail}</p>

        {event.linkedEventIds.length > 0 && (
          <p className="mt-1 text-xs text-slate-500">
            연결: {event.linkedEventIds.join(", ")}
          </p>
        )}

        <div className="mt-2 flex gap-2">
          {event.type === "ASSESSMENT" && <FormLink href={formUrl.assessment(event.refId)} />}
          {event.type === "INCIDENT" && <FormLink href={formUrl.incident(event.refId)} />}
          {event.type === "WORK_PLAN" && <FormLink href={formUrl.workPlan(event.refId)} />}
        </div>
      </button>
    </li>
  );
}

function FormLink({ href }: { href: string }) {
  return (
    <a
      className="rounded border px-2 py-1 text-xs hover:bg-slate-50"
      href={href}
      target="_blank"
      rel="noreferrer"
      onClick={(e) => e.stopPropagation()}
    >
      법정 서식
    </a>
  );
}
