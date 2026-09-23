import { useRef } from "react";

import Button from "@/components/ui/Button";
import Card from "@/components/ui/Card";
import FormField from "@/components/ui/FormField";
import Select from "@/components/ui/Select";
import type { EquipmentItem } from "@/types/equipment";

interface Props { equipment: EquipmentItem[]; equipmentId: number | null; onEquipmentChange: (id: number | null) => void; preview: string | null; analyzing: boolean; progress: string | null; onPick: (f: File | undefined) => void }

export default function UploadPanel({ equipment, equipmentId, onEquipmentChange, preview, analyzing, progress, onPick }: Props) {
  const fileRef = useRef<HTMLInputElement>(null);
  return (
    <Card>
      <FormField label="대상 설비">
        <Select value={equipmentId ?? ""} onChange={(e) => onEquipmentChange(e.target.value ? Number(e.target.value) : null)}>
          <option value="">(설비 지정 없음)</option>
          {equipment.map((e) => <option key={e.id} value={e.id}>{e.name} — {e.locationTag ?? "-"}</option>)}
        </Select>
      </FormField>
      <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={(e) => onPick(e.target.files?.[0])} />
      <div className="mt-3">
        {preview ? <img src={preview} alt="판독 대상" className="w-full rounded-md border border-slate-200 bg-slate-50 object-contain" />
          : <div className="flex h-56 items-center justify-center rounded-md border border-dashed border-slate-300 bg-slate-50 text-sm text-slate-400">사진을 선택하세요</div>}
      </div>
      <Button className="mt-3 w-full" loading={analyzing} onClick={() => fileRef.current?.click()}>{analyzing ? progress ?? "판독 중…" : "사진 선택"}</Button>
    </Card>
  );
}
