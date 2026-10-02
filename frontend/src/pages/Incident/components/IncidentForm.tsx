// IncidentForm.tsx — 사고 보고 입력. 설비, 발생형태, 재해 정도는 기본값 없이 고른다. 설비를 고르면 그 설비의 작업만 연결 후보로 보인다.
import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import FormField from "@/components/ui/FormField";
import Input from "@/components/ui/Input";
import Select from "@/components/ui/Select";
import Textarea from "@/components/ui/Textarea";
import OccurredAtInput from "@/pages/Incident/components/OccurredAtInput";
import type { IncidentFormErrors, IncidentFormState } from "@/pages/Incident/utils/incidentForm";
import { shortDate } from "@/pages/Incident/utils/priorRecord";
import { SEVERITY_LABEL, type IncidentSeverity } from "@/types/domain";
import type { EquipmentItem } from "@/types/equipment";
import { INCIDENT_TYPE_LABEL, type IncidentType } from "@/types/incident";
import type { WorkPlanListItem } from "@/types/workPlan";

interface Props {
  form: IncidentFormState;
  setForm: (f: IncidentFormState) => void;
  equipment: EquipmentItem[];
  plans: WorkPlanListItem[];
  busy: boolean;
  error: string | null;
  fieldErrors: IncidentFormErrors;
  /** 발생 일시 상한(지금, datetime-local 형식) */
  maxOccurredAt: string;
  onSubmit: () => void;
}

const SEVERITY_ORDER: IncidentSeverity[] = ["NEAR_MISS", "INJURY", "LOST_TIME", "FATALITY"];

export default function IncidentForm({
  form,
  setForm,
  equipment,
  plans,
  busy,
  error,
  fieldErrors,
  maxOccurredAt,
  onSubmit,
}: Props) {
  const set =
    <K extends keyof IncidentFormState>(k: K) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setForm({ ...form, [k]: e.target.value });

  // 연결 후보는 고른 설비의 계획서만. 설비를 바꾸면 이전 연결은 풀린다
  const equipmentPlans = plans.filter((p) => form.equipmentId && String(p.equipmentId) === form.equipmentId);
  const nearMiss = form.severity === "NEAR_MISS";
  const lostTime = form.severity === "LOST_TIME";
  const leave = form.leaveDays === "" ? null : Number(form.leaveDays);
  const reportable = form.severity === "FATALITY" || (leave !== null && leave >= 3 && !nearMiss);

  return (
    <form
      aria-label="사고 보고"
      noValidate
      className="rounded-xl border border-slate-200 bg-white shadow-card"
      onSubmit={(e) => {
        e.preventDefault();
        if (!busy) onSubmit();
      }}
    >
      <fieldset disabled={busy} className="grid gap-x-4 gap-y-3 px-6 pb-4 pt-5 md:grid-cols-6">
        <FormField label="사고 설비" required error={fieldErrors.equipmentId} className="md:col-span-2">
          <Select
            value={form.equipmentId}
            aria-invalid={fieldErrors.equipmentId ? true : undefined}
            onChange={(e) => setForm({ ...form, equipmentId: e.target.value, workPlanId: "" })}
          >
            <option value="" disabled>
              설비 선택
            </option>
            {equipment.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name}
                {e.locationTag ? ` (${e.locationTag})` : ""}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="관련 작업" className="md:col-span-2">
          <Select value={form.workPlanId} onChange={set("workPlanId")}>
            <option value="">(없음)</option>
            {equipmentPlans.map((p) => (
              <option key={p.id} value={p.id}>
                {p.workName} ({shortDate(p.workDate)})
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="발생 일시" required error={fieldErrors.occurredAt} className="md:col-span-2">
          <OccurredAtInput
            aria-label="발생 일시"
            value={form.occurredAt}
            max={maxOccurredAt}
            invalid={Boolean(fieldErrors.occurredAt)}
            onChange={(v) => setForm({ ...form, occurredAt: v })}
          />
        </FormField>

        <FormField label="발생형태" required error={fieldErrors.incidentType} className="md:col-span-2">
          <Select
            value={form.incidentType}
            aria-invalid={fieldErrors.incidentType ? true : undefined}
            onChange={(e) => setForm({ ...form, incidentType: e.target.value as IncidentType })}
          >
            <option value="" disabled>
              발생형태 선택
            </option>
            {(Object.keys(INCIDENT_TYPE_LABEL) as IncidentType[]).map((a) => (
              <option key={a} value={a}>
                {INCIDENT_TYPE_LABEL[a]}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="재해 정도" required error={fieldErrors.severity}>
          <Select
            value={form.severity}
            aria-invalid={fieldErrors.severity ? true : undefined}
            onChange={(e) => {
              const severity = e.target.value as IncidentSeverity;
              setForm({ ...form, severity, ...(severity === "NEAR_MISS" ? { leaveDays: "", injuryType: "", injuryPart: "" } : {}) });
            }}
          >
            <option value="" disabled>
              선택
            </option>
            {SEVERITY_ORDER.map((s) => (
              <option key={s} value={s}>
                {SEVERITY_LABEL[s]}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField
          label="휴업예상일수"
          required={lostTime}
          error={fieldErrors.leaveDays}
          hint={reportable ? "조사표 제출 대상" : undefined}
        >
          <Input
            type="number"
            min={lostTime ? 1 : 0}
            inputMode="numeric"
            disabled={nearMiss}
            error={Boolean(fieldErrors.leaveDays)}
            value={form.leaveDays}
            onChange={set("leaveDays")}
          />
        </FormField>
        <FormField label="상해 종류">
          <Input placeholder="예: 골절" disabled={nearMiss} value={form.injuryType} onChange={set("injuryType")} />
        </FormField>
        <FormField label="상해 부위">
          <Input placeholder="예: 왼쪽 발목" disabled={nearMiss} value={form.injuryPart} onChange={set("injuryPart")} />
        </FormField>

        <FormField label="재해 경위" className="md:col-span-6">
          <Textarea
            rows={2}
            placeholder="예: 차양부 천장 도장 중 이동식 사다리에서 중심을 잃고 떨어짐"
            value={form.description}
            onChange={set("description")}
          />
        </FormField>
      </fieldset>

      <div className="flex flex-wrap items-center gap-4 px-6 pb-5">
        {error && (
          <Callout tone="high" className="py-2">
            {error}
          </Callout>
        )}
        <Button type="submit" loading={busy} className="ml-auto min-w-36">
          사고 보고
        </Button>
      </div>
    </form>
  );
}
