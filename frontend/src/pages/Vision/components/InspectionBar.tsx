// InspectionBar.tsx — 점검 정보 한 줄: 설비, 점검자, 참여 근로자(칩 입력). 시행규칙 제37조의4 기록 항목이다.
import { X } from "lucide-react";
import { useState } from "react";

import Input from "@/components/ui/Input";
import Select from "@/components/ui/Select";
import { addParticipants } from "@/pages/Vision/utils/inspection";
import type { EquipmentItem } from "@/types/equipment";

interface Props {
  equipment: EquipmentItem[];
  equipmentId: number | null;
  onEquipmentChange: (id: number | null) => void;
  /** 사진을 올린 뒤에는 설비를 바꾸지 않는다(이미 그 설비로 기록됐다) */
  equipmentLocked: boolean;
  inspector: string;
  onInspectorChange: (v: string) => void;
  onInspectorCommit: () => void;
  participants: string[];
  onParticipantsChange: (next: string[]) => void;
}

const LABEL = "mb-1 block text-xs font-bold tracking-wide text-slate-500";

export default function InspectionBar({
  equipment, equipmentId, onEquipmentChange, equipmentLocked,
  inspector, onInspectorChange, onInspectorCommit, participants, onParticipantsChange,
}: Props) {
  const [draft, setDraft] = useState("");

  const commitDraft = () => {
    if (!draft.trim()) return;
    onParticipantsChange(addParticipants(participants, draft));
    setDraft("");
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.nativeEvent.isComposing) return;
    if (e.key === "Enter" || e.key === ",") {
      e.preventDefault();
      commitDraft();
    } else if (e.key === "Backspace" && draft === "" && participants.length > 0) {
      onParticipantsChange(participants.slice(0, -1));
    }
  };

  return (
    <section aria-label="점검 정보" className="mb-5 grid grid-cols-[minmax(0,18rem)_minmax(0,11rem)_minmax(0,1fr)] items-end gap-4 rounded-xl border border-slate-200 bg-white px-5 py-4 shadow-card">
      <label className="min-w-0">
        <span className={LABEL}>설비</span>
        <Select aria-label="설비" value={equipmentId ?? ""} disabled={equipmentLocked} onChange={(e) => onEquipmentChange(e.target.value ? Number(e.target.value) : null)}>
          <option value="">설비 선택</option>
          {equipment.map((e) => <option key={e.id} value={e.id}>{e.locationTag ? `${e.name} (${e.locationTag})` : e.name}</option>)}
        </Select>
      </label>

      <label className="min-w-0">
        <span className={LABEL}>점검자</span>
        <Input aria-label="점검자" value={inspector} onChange={(e) => onInspectorChange(e.target.value)} onBlur={onInspectorCommit} />
      </label>

      <div className="min-w-0">
        <span id="participants-label" className={LABEL}>참여 근로자</span>
        <div className="flex min-h-[2.375rem] flex-wrap items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-2 py-1 focus-within:border-slate-500 focus-within:ring-1 focus-within:ring-slate-500">
          {participants.map((p) => (
            <span key={p} className="inline-flex items-center gap-1 rounded border border-slate-200 bg-slate-50 py-0.5 pl-2 pr-1 text-sm font-medium text-slate-800">
              {p}
              <button type="button" aria-label={`${p} 빼기`} className="rounded p-0.5 text-slate-400 hover:bg-slate-200 hover:text-slate-700" onClick={() => onParticipantsChange(participants.filter((x) => x !== p))}>
                <X aria-hidden className="h-3.5 w-3.5" />
              </button>
            </span>
          ))}
          <input
            aria-labelledby="participants-label"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={onKeyDown}
            onBlur={commitDraft}
            placeholder={participants.length === 0 ? "참여 근로자 추가" : ""}
            className="min-w-[8rem] flex-1 border-0 bg-transparent px-1 py-1 text-sm outline-none placeholder:text-slate-400"
          />
        </div>
      </div>
    </section>
  );
}
