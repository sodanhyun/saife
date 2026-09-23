import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import Card from "@/components/ui/Card";
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

/** 사고 등록 입력 폼 — 등록 버튼 하나로 소환·수시평가·조사표 초안이 동시에 만들어진다. */
export default function IncidentForm({ form, setForm, equipment, plans, busy, error, onSubmit }: Props) {
  const set =
    <K extends keyof IncidentFormState>(k: K) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setForm({ ...form, [k]: e.target.value });
  return (
    <Card>
      <div className="grid gap-3 md:grid-cols-3">
        <FormField label="사고 설비">
          {/* effectiveForm이 전달되므로 null은 실제로 오지 않지만, 타입이 string | null이라 안전하게 처리 */}
          <Select value={form.equipmentId ?? ""} onChange={set("equipmentId")}>
            <option value="">(설비 미상)</option>
            {equipment.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name} — {e.locationTag ?? "-"}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="관련 작업계획서">
          <Select value={form.workPlanId} onChange={set("workPlanId")}>
            <option value="">(없음)</option>
            {plans.map((p) => (
              <option key={p.id} value={p.id}>
                #{p.id} {p.workName} ({p.workDate})
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="발생 일시" required>
          <Input type="datetime-local" value={form.occurredAt} onChange={set("occurredAt")} />
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
        <FormField label="휴업일수" hint="3일 이상이면 조사표 제출 대상">
          <Input type="number" min={0} value={form.leaveDays} onChange={set("leaveDays")} />
        </FormField>
        <FormField label="재해 경위" className="md:col-span-2">
          <Textarea
            rows={2}
            placeholder="예: 차양부 천장 도장 작업 중 사다리 상부에서 중심을 잃고 약 3.2m 아래로 추락"
            value={form.description}
            onChange={set("description")}
          />
        </FormField>
        <FormField label="재해자">
          <Input value={form.victimName} onChange={set("victimName")} />
        </FormField>
      </div>
      <div className="mt-4 flex items-center gap-3">
        <Button variant="danger" loading={busy} onClick={onSubmit}>
          사고 등록
        </Button>
        {error && (
          <Callout tone="high" className="flex-1 py-2">
            {error}
          </Callout>
        )}
      </div>
    </Card>
  );
}
