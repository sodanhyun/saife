// PhotoPanel.tsx — 점검 설비 선택, 현장 사진(크게), 판독 파이프라인 진행.
import { Camera } from "lucide-react";
import { useRef, useState } from "react";

import Button from "@/components/ui/Button";
import Select from "@/components/ui/Select";
import cn from "@/lib/cn";
import AnalysisProgress from "@/pages/Vision/components/AnalysisProgress";
import type { AnalysisStage, StageMark } from "@/pages/Vision/hooks/useVisionStream";
import type { EquipmentItem } from "@/types/equipment";

interface Props {
  equipment: EquipmentItem[];
  equipmentId: number | null;
  onEquipmentChange: (id: number | null) => void;
  preview: string | null;
  analyzing: boolean;
  stage: AnalysisStage | null;
  stageLog: StageMark[];
  failed: boolean;
  assessmentId: number | null;
  onPick: (f: File | undefined) => void;
}

export default function PhotoPanel({ equipment, equipmentId, onEquipmentChange, preview, analyzing, stage, stageLog, failed, assessmentId, onPick }: Props) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const choose = () => fileRef.current?.click();

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragging(false);
    if (!analyzing) onPick(e.dataTransfer.files?.[0]);
  };

  return (
    <section aria-label="현장 사진" className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-card">
      <header className="flex items-end gap-3 border-b border-slate-100 px-5 py-4">
        <label className="min-w-0 flex-1">
          <span className="mb-1 block text-xs font-bold tracking-wide text-slate-500">점검 설비</span>
          <Select aria-label="점검 설비" value={equipmentId ?? ""} disabled={analyzing} onChange={(e) => onEquipmentChange(e.target.value ? Number(e.target.value) : null)}>
            <option value="">(설비 지정 없음)</option>
            {equipment.map((e) => <option key={e.id} value={e.id}>{e.locationTag ? `${e.name} (${e.locationTag})` : e.name}</option>)}
          </Select>
        </label>
        {preview && (
          <Button variant="secondary" disabled={analyzing} onClick={choose}>다른 사진</Button>
        )}
      </header>

      <input ref={fileRef} type="file" aria-label="사진 파일" accept="image/*" className="hidden" onChange={(e) => { onPick(e.target.files?.[0]); e.target.value = ""; }} />

      <div className="p-5" onDragOver={(e) => { e.preventDefault(); setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={onDrop}>
        {preview ? (
          <figure className="relative aspect-video overflow-hidden rounded-xl bg-slate-900">
            <img src={preview} alt="점검 대상 현장 사진" className="h-full w-full object-contain animate-fade-in" />
            {analyzing && (
              <figcaption className="absolute left-3 top-3 flex items-center gap-2 rounded-md bg-white px-2.5 py-1 text-xs font-semibold text-slate-700 shadow-card">
                <span className="relative flex h-2 w-2">
                  <span aria-hidden className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />
                  <span className="relative h-2 w-2 rounded-full bg-brand" />
                </span>
                판독 중
              </figcaption>
            )}
            {!analyzing && assessmentId !== null && (
              <figcaption className="absolute left-3 top-3 rounded-md bg-white px-2.5 py-1 text-xs font-semibold text-slate-700 shadow-card animate-fade-in">
                평가 #{assessmentId}, 상시 위험성평가 (사진 판독)
              </figcaption>
            )}
          </figure>
        ) : (
          <div className={cn("flex aspect-video flex-col items-center justify-center rounded-xl border-2 border-dashed px-6 text-center transition-colors", dragging ? "border-brand bg-brand-soft" : "border-slate-300 bg-slate-50")}>
            <Camera aria-hidden className="h-9 w-9 text-slate-400" strokeWidth={1.75} />
            <p className="mt-3 text-stage font-semibold text-slate-900">현장 사진을 올리세요</p>
            <p className="mt-1 text-sm text-slate-500">끌어다 놓거나 파일을 고릅니다. 사진에 물리적으로 보이는 것만 판정합니다.</p>
            <Button className="mt-4" size="lg" onClick={choose}>사진 선택</Button>
          </div>
        )}
      </div>

      <AnalysisProgress stage={stage} stageLog={stageLog} analyzing={analyzing} failed={failed} />
    </section>
  );
}
