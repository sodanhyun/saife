// PhotoPanel.tsx: 현장 사진. 분석 중에는 사진 위 한 줄 진행 표시, 분석 뒤에는 위험요인 위치를 번호 상자로 표시한다.
import { Camera } from "lucide-react";
import { useRef, useState } from "react";

import Button from "@/components/ui/Button";
import cn from "@/lib/cn";
import { STAGE_TEXT, type AnalysisStage } from "@/pages/Vision/hooks/useVisionStream";
import type { RiskLevel } from "@/types/domain";
import { riskColor } from "@/utils/statusColors";

/** 사진 위 표시 하나. 번호는 오른쪽 위험요인 카드의 번호와 같다 */
export interface PhotoMark {
  no: number;
  box: number[];
  level: RiskLevel;
  label: string;
}

interface Props {
  preview: string | null;
  analyzing: boolean;
  stage: AnalysisStage | null;
  onPick: (f: File | undefined) => void;
  marks?: PhotoMark[];
  /** 첫 화면의 큰 업로드 영역 */
  hero?: boolean;
}

const BOX_BORDER: Record<RiskLevel, string> = {
  HIGH: "border-risk-high",
  MEDIUM: "border-risk-medium",
  LOW: "border-risk-low",
};

/** 사진 실제 비율에 맞춘 틀 안에 상자를 % 좌표로 둔다(object-contain 여백에 상자가 밀리지 않게) */
function MarkedImage({ src, marks }: { src: string; marks: PhotoMark[] }) {
  const [ratio, setRatio] = useState<number | null>(null);
  const wide = ratio !== null && ratio > 16 / 9;
  return (
    <div className="flex h-full w-full items-center justify-center">
      <div className={cn("relative", wide ? "w-full" : "h-full")} style={{ aspectRatio: ratio ?? 16 / 9 }}>
        <img src={src} alt="순회점검 현장 사진" className="h-full w-full object-contain animate-fade-in"
          onLoad={(e) => setRatio(e.currentTarget.naturalWidth / e.currentTarget.naturalHeight)} />
        {ratio !== null && marks.map((m, i) => {
          const [y0, x0, y1, x1] = m.box;
          const c = riskColor(m.level);
          return (
            <div key={m.no} aria-label={`위치 ${m.no} ${m.label}`}
              className={cn("absolute rounded-sm border-2 ring-1 ring-white/70 animate-fade-in", BOX_BORDER[m.level])}
              style={{ top: `${y0 / 10}%`, left: `${x0 / 10}%`, height: `${(y1 - y0) / 10}%`, width: `${(x1 - x0) / 10}%`, animationDelay: `${200 + i * 250}ms` }}>
              <span className={cn("absolute -top-px left-0 flex -translate-y-full items-center gap-1.5 whitespace-nowrap rounded-t-sm px-1.5 py-0.5 text-xs font-bold text-white", c.solid)}>
                <span className="tabular-nums">{m.no}</span>{m.label}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default function PhotoPanel({ preview, analyzing, stage, onPick, marks = [], hero = false }: Props) {
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
            <MarkedImage src={preview} marks={analyzing ? [] : marks} />
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
          <div className={cn("flex flex-col items-center justify-center rounded-lg border-2 border-dashed px-6 text-center transition-colors",
            hero ? "py-16" : "aspect-video",
            dragging ? "border-brand bg-brand-soft" : hero ? "border-brand-line bg-brand-soft/40" : "border-slate-300 bg-slate-50")}>
            <span className={cn("grid place-items-center rounded-full", hero ? "h-16 w-16 bg-brand text-white" : "")}>
              <Camera aria-hidden className={hero ? "h-8 w-8" : "h-9 w-9 text-slate-400"} strokeWidth={1.75} />
            </span>
            <p className={cn("mt-4 font-semibold text-slate-900", hero ? "text-headline" : "text-stage")}>현장 사진</p>
            {hero && <p className="mt-1.5 text-sm text-slate-500">안전 문제, 위험성, 예방 방법</p>}
            <Button className="mt-5" size="lg" onClick={choose}>사진 선택</Button>
          </div>
        )}
      </div>
    </section>
  );
}
