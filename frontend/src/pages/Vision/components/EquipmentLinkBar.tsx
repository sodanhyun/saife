// EquipmentLinkBar.tsx: 사진만으로 분석한 점검을 설비 기록에 붙인다. 사진 속 설비로 찾은 설비를 미리 고른다.
import { useState } from "react";

import Button from "@/components/ui/Button";
import Select from "@/components/ui/Select";
import type { EquipmentItem } from "@/types/equipment";

interface Props {
  equipment: EquipmentItem[];
  photoEquipment: string | null;
  suggestedId: number | null;
  busy: boolean;
  onAssign: (equipmentId: number) => void;
}

export default function EquipmentLinkBar({ equipment, photoEquipment, suggestedId, busy, onAssign }: Props) {
  const [picked, setPicked] = useState<number | null>(suggestedId);
  return (
    <section aria-label="설비 기록" className="flex flex-wrap items-center gap-3 rounded-xl border border-slate-200 bg-white px-5 py-3.5 shadow-card">
      <div className="min-w-0">
        <p className="text-xs font-semibold text-slate-500">사진 속 설비</p>
        <p className="text-sm font-semibold text-slate-900">{photoEquipment ?? "-"}</p>
      </div>
      <div className="ml-auto flex items-center gap-2">
        <Select aria-label="기록할 설비" value={picked ?? ""} onChange={(e) => setPicked(e.target.value ? Number(e.target.value) : null)} className="w-64">
          <option value="">설비 선택</option>
          {equipment.map((e) => (
            <option key={e.id} value={e.id}>{e.locationTag ? `${e.name} (${e.locationTag})` : e.name}</option>
          ))}
        </Select>
        <Button size="sm" loading={busy} disabled={picked === null} onClick={() => picked !== null && onAssign(picked)}>설비에 기록</Button>
      </div>
    </section>
  );
}
