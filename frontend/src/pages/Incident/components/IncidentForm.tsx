// IncidentForm.tsx — 사고 등록 입력. 설비를 고르면 그 설비의 작업계획서만 연결 후보로 보인다.
import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import DateInput from "@/components/ui/DateInput";
import FormField from "@/components/ui/FormField";
import Input from "@/components/ui/Input";
import Select from "@/components/ui/Select";
import Textarea from "@/components/ui/Textarea";
import type { IncidentFormState } from "@/pages/Incident/utils/incidentForm";
import { ACCIDENT_LABEL, SEVERITY_LABEL, type AccidentType, type IncidentSeverity } from "@/types/domain";
import type { EquipmentItem } from "@/types/equipment";
import type { WorkPlanListItem } from "@/types/workPlan";

interface Props {
  form: IncidentFormState;
  setForm: (f: IncidentFormState) => void;
  equipment: EquipmentItem[];
  plans: WorkPlanListItem[];
  busy: boolean;
  error: string | null;
  onSubmit: () => void;
}

export default function IncidentForm({ form, setForm, equipment, plans, busy, error, onSubmit }: Props) {
  const set =
    <K extends keyof IncidentFormState>(k: K) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setForm({ ...form, [k]: e.target.value });

  // 연결 후보는 고른 설비의 계획서만. 설비를 바꾸면 이전 연결은 풀린다
  const equipmentPlans = plans.filter((p) => form.equipmentId && String(p.equipmentId) === form.equipmentId);
  const leave = form.leaveDays === "" ? null : Number(form.leaveDays);
  const reportable = leave !== null && leave >= 3;

  return (
    <form
      aria-label="사고 등록"
      className="rounded-xl border border-slate-200 bg-white shadow-card"
      onSubmit={(e) => {
        e.preventDefault();
        if (!busy) onSubmit();
      }}
    >
      <fieldset disabled={busy} className="grid gap-x-4 gap-y-3 px-6 pb-5 pt-5 md:grid-cols-6">
        <FormField label="사고 설비" className="md:col-span-2">
          <Select
            value={form.equipmentId ?? ""}
            onChange={(e) => setForm({ ...form, equipmentId: e.target.value, workPlanId: "" })}
          >
            <option value="">(설비 미상)</option>
            {equipment.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name}
                {e.locationTag ? ` (${e.locationTag})` : ""}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="관련 작업계획서" className="md:col-span-2">
          <Select value={form.workPlanId} onChange={set("workPlanId")}>
            <option value="">{equipmentPlans.length === 0 ? "(이 설비의 계획서 없음)" : "(연결 안 함)"}</option>
            {equipmentPlans.map((p) => (
              <option key={p.id} value={p.id}>
                #{p.id} {p.workName} ({p.workDate})
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="발생 일시" required className="md:col-span-2">
          <DateInput type="datetime-local" aria-label="발생 일시" value={form.occurredAt} onChange={(v) => setForm({ ...form, occurredAt: v })} />
        </FormField>

        <FormField label="발생형태">
          <Select
            value={form.accidentType}
            onChange={(e) => setForm({ ...form, accidentType: e.target.value as AccidentType })}
          >
            {(Object.keys(ACCIDENT_LABEL) as AccidentType[]).map((a) => (
              <option key={a} value={a}>
                {ACCIDENT_LABEL[a]}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="재해 정도">
          <Select
            value={form.severity}
            onChange={(e) => setForm({ ...form, severity: e.target.value as IncidentSeverity })}
          >
            {(Object.keys(SEVERITY_LABEL) as IncidentSeverity[]).map((s) => (
              <option key={s} value={s}>
                {SEVERITY_LABEL[s]}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="휴업일수" hint={reportable ? "조사표 제출 대상" : "3일 이상이면 조사표 대상"}>
          <Input type="number" min={0} value={form.leaveDays} onChange={set("leaveDays")} />
        </FormField>
        <FormField label="재해 경위" className="md:col-span-3">
          <Textarea
            rows={1}
            placeholder="예: 천장 도장 작업 중 사다리 상부에서 중심을 잃고 약 3.2m 아래로 추락"
            value={form.description}
            onChange={set("description")}
          />
        </FormField>
      </fieldset>

      <div className="flex flex-wrap items-center gap-4 border-t border-slate-100 bg-slate-50 px-6 py-3.5">
        <p className="text-sm text-slate-600">
          등록하면 이 설비의 사전 기록 소환, 수시평가 생성, 조사표 기한 계산, 작업계획서 경고가 한 번에 진행됩니다.
        </p>
        {error && (
          <Callout tone="high" className="py-2">
            {error}
          </Callout>
        )}
        <Button type="submit" variant="danger" size="lg" loading={busy} className="ml-auto min-w-36">
          사고 등록
        </Button>
      </div>
    </form>
  );
}
