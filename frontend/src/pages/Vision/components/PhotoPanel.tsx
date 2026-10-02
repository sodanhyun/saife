// PhotoPanel.tsx — 현장 사진. 분석 중에는 사진 위 한 줄 진행 표시만 둔다.
import { Camera } from "lucide-react";
import { useRef, useState } from "react";

import Button from "@/components/ui/Button";
import cn from "@/lib/cn";
import { STAGE_TEXT, type AnalysisStage } from "@/pages/Vision/hooks/useVisionStream";

interface Props {
  preview: string | null;
  analyzing: boolean;
  stage: AnalysisStage | null;
  onPick: (f: File | undefined) => void;
}

export default function PhotoPanel({ preview, analyzing, stage, onPick }: Props) {
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
      <input ref={fileRef} type="file" aria-label="사진 파일" accept="image/*" className="hidden" onChange={(e) => { onPick(e.target.files?.[0]); e.target.value = ""; }} />

      <div className="p-4" onDragOver={(e) => { e.preventDefault(); setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={onDrop}>
        {preview ? (
          <figure className="relative aspect-video overflow-hidden rounded-lg bg-slate-900">
            <img src={preview} alt="순회점검 현장 사진" className="h-full w-full object-contain animate-fade-in" />
            {analyzing && stage && (
              <figcaption className="absolute left-3 top-3 flex items-center gap-2 rounded-md bg-white px-2.5 py-1 text-sm font-semibold text-slate-700 shadow-card">
                <span className="relative flex h-2 w-2">
                  <span aria-hidden className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />
                  <span className="relative h-2 w-2 rounded-full bg-brand" />
                </span>
                {STAGE_TEXT[stage]}
              </figcaption>
            )}
            {!analyzing && (
              <Button size="sm" variant="secondary" className="absolute bottom-3 right-3" onClick={choose}>다른 사진</Button>
            )}
          </figure>
        ) : (
          <div className={cn("flex aspect-video flex-col items-center justify-center rounded-lg border-2 border-dashed px-6 text-center transition-colors", dragging ? "border-brand bg-brand-soft" : "border-slate-300 bg-slate-50")}>
            <Camera aria-hidden className="h-9 w-9 text-slate-400" strokeWidth={1.75} />
            <p className="mt-3 text-stage font-semibold text-slate-900">현장 사진</p>
            <Button className="mt-4" size="lg" onClick={choose}>사진 선택</Button>
          </div>
        )}
      </div>
    </section>
  );
}
