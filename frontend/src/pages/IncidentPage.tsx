import { useCallback, useEffect, useState } from "react";
import { equipmentApi, formUrl, incidentApi, workPlanApi } from "@/api/saifeApi";
import { ACCIDENT_LABEL, RISK_CLASS, RISK_LABEL, SEVERITY_LABEL } from "@/types/domain";
import type { AccidentType, IncidentSeverity } from "@/types/domain";
import type { EquipmentItem } from "@/types/equipment";
import type { IncidentListItem, IncidentRegisterResponse } from "@/types/incident";
import type { WorkPlanListItem } from "@/types/workPlan";

/**
 * UC2 — 사고 등록과 콜백.
 *
 * 등록 버튼 하나로 네 가지가 동시에 일어난다: 법정 기한 판정 · 설비 이력 소환 ·
 * 수시평가 자동 생성 · 조사표 문안 초안. 시연 영상 2:20~2:45가 이 화면이다.
 *
 * <b>소환 결과를 맨 위에 둔다.</b> 이 화면에서 가장 중요한 것은 등록 완료 메시지가
 * 아니라 "이 사고는 예고되어 있었습니다"라는 한 줄이다.
 */
export function IncidentPage() {
  const [equipment, setEquipment] = useState<EquipmentItem[]>([]);
  const [plans, setPlans] = useState<WorkPlanListItem[]>([]);
  const [list, setList] = useState<IncidentListItem[]>([]);
  const [response, setResponse] = useState<IncidentRegisterResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [form, setForm] = useState({
    equipmentId: "" as string,
    workPlanId: "" as string,
    occurredAt: defaultOccurredAt(),
    victimName: "",
    severity: "LOST_TIME" as IncidentSeverity,
    leaveDays: "14",
    accidentType: "FALL" as AccidentType,
    description: "",
  });

  const refreshList = useCallback(async () => {
    const page = await incidentApi.list(0, 20);
    setList(page.content);
  }, []);

  // 마운트 시 1회. 이후 목록 갱신은 등록 핸들러가 직접 부른다
  useEffect(() => {
    void equipmentApi.list().then((e) => {
      setEquipment(e);
      setForm((f) => (f.equipmentId ? f : { ...f, equipmentId: String(e[0]?.id ?? "") }));
    });
    void workPlanApi.list(0, 20).then((p) => setPlans(p.content));
    void incidentApi.list(0, 20).then((p) => setList(p.content));
  }, []);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await incidentApi.register({
        equipmentId: form.equipmentId ? Number(form.equipmentId) : null,
        workPlanId: form.workPlanId ? Number(form.workPlanId) : null,
        occurredAt: toOffsetIso(form.occurredAt),
        victimName: form.victimName || null,
        severity: form.severity,
        // 빈 값을 0으로 바꾸지 않는다. 미입력은 "모른다"이고 서버가 판단 보류로 처리한다
        leaveDays: form.leaveDays === "" ? null : Number(form.leaveDays),
        accidentType: form.accidentType,
        description: form.description || null,
      });
      setResponse(res);
      await refreshList();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="h-full overflow-auto p-6">
      <header>
        <h1 className="text-xl font-semibold">산업재해 등록</h1>
        <p className="text-sm text-slate-500">
          등록하면 같은 설비의 이력을 소환하고, 수시평가를 만들고, 법정 제출 기한을 계산합니다.
        </p>
      </header>

      <section className="mt-4 grid gap-3 rounded border bg-white p-4 md:grid-cols-3">
        <label className="text-sm">
          <span className="block text-slate-600">사고 설비</span>
          <select
            className="mt-1 w-full rounded border px-3 py-2"
            value={form.equipmentId}
            onChange={(e) => setForm({ ...form, equipmentId: e.target.value })}
          >
            <option value="">(설비 미상)</option>
            {equipment.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name} — {e.locationTag ?? "-"}
              </option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          <span className="block text-slate-600">관련 작업계획서</span>
          <select
            className="mt-1 w-full rounded border px-3 py-2"
            value={form.workPlanId}
            onChange={(e) => setForm({ ...form, workPlanId: e.target.value })}
          >
            <option value="">(없음)</option>
            {plans.map((p) => (
              <option key={p.id} value={p.id}>
                #{p.id} {p.workName} ({p.workDate})
              </option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          <span className="block text-slate-600">발생 일시</span>
          <input
            type="datetime-local"
            className="mt-1 w-full rounded border px-3 py-2"
            value={form.occurredAt}
            onChange={(e) => setForm({ ...form, occurredAt: e.target.value })}
          />
        </label>

        <label className="text-sm">
          <span className="block text-slate-600">발생형태</span>
          <select
            className="mt-1 w-full rounded border px-3 py-2"
            value={form.accidentType}
            onChange={(e) => setForm({ ...form, accidentType: e.target.value as AccidentType })}
          >
            {(Object.keys(ACCIDENT_LABEL) as AccidentType[]).map((a) => (
              <option key={a} value={a}>
                {ACCIDENT_LABEL[a]}
              </option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          <span className="block text-slate-600">재해 정도</span>
          <select
            className="mt-1 w-full rounded border px-3 py-2"
            value={form.severity}
            onChange={(e) => setForm({ ...form, severity: e.target.value as IncidentSeverity })}
          >
            {(Object.keys(SEVERITY_LABEL) as IncidentSeverity[]).map((s) => (
              <option key={s} value={s}>
                {SEVERITY_LABEL[s]}
              </option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          <span className="block text-slate-600">
            휴업일수 <span className="text-slate-400">(3일 이상이면 조사표 제출 대상)</span>
          </span>
          <input
            type="number"
            min={0}
            className="mt-1 w-full rounded border px-3 py-2"
            value={form.leaveDays}
            onChange={(e) => setForm({ ...form, leaveDays: e.target.value })}
          />
        </label>

        <label className="text-sm md:col-span-2">
          <span className="block text-slate-600">재해 경위</span>
          <textarea
            rows={2}
            className="mt-1 w-full rounded border px-3 py-2"
            placeholder="예: 차양부 천장 도장 작업 중 사다리 상부에서 중심을 잃고 약 3.2m 아래로 추락"
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
        </label>

        <label className="text-sm">
          <span className="block text-slate-600">재해자</span>
          <input
            className="mt-1 w-full rounded border px-3 py-2"
            value={form.victimName}
            onChange={(e) => setForm({ ...form, victimName: e.target.value })}
          />
        </label>

        <div className="md:col-span-3">
          <button
            className="rounded bg-red-700 px-5 py-2 text-white disabled:opacity-40"
            disabled={busy}
            onClick={() => void submit()}
          >
            {busy ? "처리 중…" : "사고 등록"}
          </button>
          {error && <span className="ml-3 text-sm text-red-700">{error}</span>}
        </div>
      </section>

      {response && (
        <section className="mt-5 space-y-4">
          {/* 이 화면에서 가장 중요한 줄 */}
          <div
            className={`rounded border p-4 ${
              response.recall.predicted
                ? "border-red-400 bg-red-50 text-red-900"
                : "border-slate-300 bg-white"
            }`}
          >
            <p className="text-base font-semibold">{response.recall.headline}</p>
            <p className="mt-1 text-sm">
              {response.recall.equipmentName}
              {response.recall.locationTag ? ` · ${response.recall.locationTag}` : ""}
            </p>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <div className="rounded border bg-white p-4">
              <h2 className="font-semibold">법정 제출 기한</h2>
              <p className="mt-2 text-sm">
                <span className="font-medium">{response.reportDuty.statusLabel}</span>
                {response.reportDuty.dueDate && (
                  <>
                    {" · 기한 "}
                    <span className="tabular-nums">{response.reportDuty.dueDate}</span>
                    {response.reportDuty.daysRemaining !== null && (
                      <span
                        className={
                          response.reportDuty.daysRemaining < 0
                            ? "ml-2 font-semibold text-red-700"
                            : "ml-2 font-semibold"
                        }
                      >
                        D{response.reportDuty.daysRemaining >= 0 ? "-" : "+"}
                        {Math.abs(response.reportDuty.daysRemaining)}
                      </span>
                    )}
                  </>
                )}
              </p>
              <p className="mt-1 text-xs text-slate-500">{response.reportDuty.basis}</p>
              <a
                className="mt-3 inline-block rounded border px-3 py-1.5 text-sm hover:bg-slate-50"
                href={formUrl.incident(response.incident.id)}
                target="_blank"
                rel="noreferrer"
              >
                산업재해조사표
              </a>
            </div>

            <div className="rounded border bg-white p-4">
              <h2 className="font-semibold">
                수시평가 자동 생성
                {response.followUp.assessmentId && ` — #${response.followUp.assessmentId}`}
              </h2>
              <p className="mt-1 text-xs text-slate-500">{response.followUp.legalBasis}</p>
              <ul className="mt-2 space-y-2 text-sm">
                {response.followUp.regraded.map((r) => (
                  <li key={r.hazardId}>
                    <span className="text-slate-500">
                      {r.accidentType ? ACCIDENT_LABEL[r.accidentType] : "-"}
                    </span>{" "}
                    <span
                      className={`rounded border px-1.5 py-0.5 text-xs ${RISK_CLASS[r.after]}`}
                    >
                      {r.before && r.changed
                        ? `${RISK_LABEL[r.before]} → ${RISK_LABEL[r.after]}`
                        : `${RISK_LABEL[r.after]} 유지`}
                    </span>
                    <p className="text-xs text-slate-500">{r.ruleTrace}</p>
                  </li>
                ))}
              </ul>
              {response.followUp.assessmentId && (
                <a
                  className="mt-3 inline-block rounded border px-3 py-1.5 text-sm hover:bg-slate-50"
                  href={formUrl.assessment(response.followUp.assessmentId)}
                  target="_blank"
                  rel="noreferrer"
                >
                  위험성평가표
                </a>
              )}
            </div>
          </div>

          <div className="rounded border bg-white p-4">
            <h2 className="font-semibold">사고 전 이 설비에 기록돼 있던 사항</h2>
            <div className="mt-2 grid gap-4 md:grid-cols-2">
              <div>
                <h3 className="text-sm text-slate-500">위험요인</h3>
                <ul className="mt-1 space-y-1 text-sm">
                  {response.recall.priorHazards.length === 0 && (
                    <li className="text-slate-400">없음</li>
                  )}
                  {response.recall.priorHazards.map((h) => (
                    <li key={h.hazardId} className={h.sameAxisAsIncident ? "font-medium" : ""}>
                      [{h.accidentType ? ACCIDENT_LABEL[h.accidentType] : "-"}]{" "}
                      {h.missingControl ?? h.description}
                      {h.lastRiskLevel && (
                        <span className="ml-1 text-slate-500">
                          · 최근 평가 {RISK_LABEL[h.lastRiskLevel]} ({h.lastAssessedOn})
                        </span>
                      )}
                      {h.sameAxisAsIncident && (
                        <span className="ml-1 text-red-700">← 사고와 같은 발생형태</span>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
              <div>
                <h3 className="text-sm text-slate-500">미이행 조치</h3>
                <ul className="mt-1 space-y-1 text-sm">
                  {response.recall.unfinishedActions.length === 0 && (
                    <li className="text-slate-400">없음</li>
                  )}
                  {response.recall.unfinishedActions.map((a) => (
                    <li key={a.actionId}>
                      {a.content}
                      <span className="ml-1 text-slate-500">
                        (기한 {a.dueDate}
                        {a.overdueDays !== null && a.overdueDays > 0 && (
                          <span className="text-red-700"> · {a.overdueDays}일 경과</span>
                        )}
                        )
                      </span>
                    </li>
                  ))}
                </ul>
                {response.recall.warnedAt && (
                  <p className="mt-2 text-sm text-red-700">
                    작업 전 브리핑으로 경고 전달됨 —{" "}
                    {new Date(response.recall.warnedAt).toLocaleString("ko-KR")}
                  </p>
                )}
              </div>
            </div>
          </div>

          <div className="rounded border bg-white p-4">
            <div className="flex items-center gap-2">
              <h2 className="font-semibold">산업재해조사표 초안</h2>
              {!response.draft.aiGenerated && (
                <span className="rounded bg-slate-200 px-2 py-0.5 text-xs">AI 생성 아님</span>
              )}
            </div>
            <h3 className="mt-2 text-sm text-slate-500">재해 발생 원인</h3>
            <p className="whitespace-pre-wrap text-sm">{response.draft.cause}</p>
            <h3 className="mt-2 text-sm text-slate-500">재발 방지 계획</h3>
            <p className="whitespace-pre-wrap text-sm">{response.draft.prevention}</p>
            <p className="mt-2 text-xs text-slate-500">{response.draft.disclaimer}</p>
          </div>
        </section>
      )}

      <section className="mt-6">
        <h2 className="text-sm font-semibold text-slate-600">사고 목록 · 제출 기한</h2>
        <div className="mt-2 overflow-auto rounded border bg-white">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-left text-slate-500">
              <tr>
                <th className="px-3 py-2">발생 일시</th>
                <th className="px-3 py-2">설비</th>
                <th className="px-3 py-2">발생형태</th>
                <th className="px-3 py-2">휴업</th>
                <th className="px-3 py-2">조사표</th>
                <th className="px-3 py-2">기한</th>
                <th className="px-3 py-2">수시평가</th>
              </tr>
            </thead>
            <tbody>
              {list.length === 0 && (
                <tr>
                  <td className="px-3 py-4 text-slate-400" colSpan={7}>
                    등록된 사고가 없습니다.
                  </td>
                </tr>
              )}
              {list.map((i) => (
                <tr key={i.id} className="border-t">
                  <td className="px-3 py-2 tabular-nums">
                    {new Date(i.occurredAt).toLocaleString("ko-KR")}
                  </td>
                  <td className="px-3 py-2">{i.equipmentName ?? "-"}</td>
                  <td className="px-3 py-2">
                    {i.accidentType ? ACCIDENT_LABEL[i.accidentType] : "-"}
                  </td>
                  <td className="px-3 py-2 tabular-nums">
                    {i.leaveDays === null ? "미입력" : `${i.leaveDays}일`}
                  </td>
                  <td className="px-3 py-2">{i.reportStatusLabel}</td>
                  <td className="px-3 py-2 tabular-nums">
                    {i.reportDueDate ?? "-"}
                    {i.daysRemaining !== null && (
                      <span className={i.daysRemaining < 0 ? "ml-2 text-red-700" : "ml-2"}>
                        D{i.daysRemaining >= 0 ? "-" : "+"}
                        {Math.abs(i.daysRemaining)}
                      </span>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    {i.followUpAssessmentId ? `#${i.followUpAssessmentId}` : "-"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

/** datetime-local 기본값 — 오늘 날짜의 현재 시각 */
function defaultOccurredAt(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}`;
}

/**
 * datetime-local(로컬 시각, 오프셋 없음) → 오프셋이 붙은 ISO 문자열.
 *
 * 오프셋 없이 보내면 서버가 자기 시간대로 해석해 <b>법정 기한이 하루 밀릴 수 있다.</b>
 * 브라우저의 실제 오프셋을 붙여 보낸다.
 */
function toOffsetIso(local: string): string {
  const d = new Date(local);
  const offsetMin = -d.getTimezoneOffset();
  const sign = offsetMin >= 0 ? "+" : "-";
  const pad = (n: number) => String(Math.floor(Math.abs(n))).padStart(2, "0");
  return `${local}:00${sign}${pad(offsetMin / 60)}:${pad(offsetMin % 60)}`;
}
