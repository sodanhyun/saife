// VerifyActionModal.tsx: 이행 확인. 증빙 사진(대조 결과), 이행 내용, 확인자, 개선 후 위험성을 받아 닫는다
import { useRef } from "react";

import { Camera, CheckCircle2, CircleHelp, XCircle } from "lucide-react";

import Button from "@/components/ui/Button";
import FormField from "@/components/ui/FormField";
import Input from "@/components/ui/Input";
import Modal from "@/components/ui/Modal";
import SegmentedControl from "@/components/ui/SegmentedControl";
import cn from "@/lib/cn";
import type { VerifyDraft } from "@/pages/Action/hooks/useActions";
import { dueLabel } from "@/pages/Action/utils/actionRow";
import type { ActionListItem, PhotoCheckItem, PhotoCheckStatus } from "@/types/action";
import type { RiskLevel } from "@/types/domain";
import { riskColor } from "@/utils/statusColors";

interface Props {
  target: ActionListItem | null;
  draft: VerifyDraft;
  today: string;
  uploading: boolean;
  busy: boolean;
  onPhoto: (file: File | undefined) => void;
  onChange: (patch: Partial<VerifyDraft>) => void;
  onCancel: () => void;
  onConfirm: () => void;
}

const STATUS: Record<PhotoCheckStatus, { label: string; icon: typeof CheckCircle2; className: string }> = {
  SEEN: { label: "확인", icon: CheckCircle2, className: "text-risk-low-text" },
  NOT_SEEN: { label: "안 보임", icon: XCircle, className: "text-risk-high-text" },
  UNCLEAR: { label: "판단 불가", icon: CircleHelp, className: "text-slate-400" },
};

const LEVELS: RiskLevel[] = ["LOW", "MEDIUM", "HIGH"];
const LEVEL_LABEL: Record<RiskLevel, string> = { LOW: "하", MEDIUM: "중", HIGH: "상" };

function CheckRow({ item }: { item: PhotoCheckItem }) {
  const s = STATUS[item.status];
  const Icon = s.icon;
  return (
    <li className="flex gap-2.5">
      <Icon aria-hidden className={cn("mt-0.5 h-4 w-4 shrink-0", s.className)} />
      <div className="min-w-0">
        <p className="text-sm font-semibold text-slate-900">
          {item.item}
          <span className={cn("ml-2 text-xs font-bold", s.className)}>{s.label}</span>
        </p>
        {item.evidence && <p className="mt-0.5 text-xs leading-relaxed text-slate-500">{item.evidence}</p>}
      </div>
    </li>
  );
}

export default function VerifyActionModal({ target, draft, today, uploading, busy, onPhoto, onChange, onCancel, onConfirm }: Props) {
  const fileRef = useRef<HTMLInputElement>(null);
  const evidence = draft.evidence;
  const high = draft.residualLevel === "HIGH";
  const ready = !!evidence?.evidenceUrl && draft.verifiedBy.trim() !== "" && draft.residualLevel !== null && !high;
  const meta = target ? [target.equipmentName, target.owner, dueLabel(target, today)].filter(Boolean).join("  /  ") : "";

  return (
    <Modal
      isOpen={target !== null}
      onClose={onCancel}
      title="이행 확인"
      maxWidth="2xl"
      closeOnOutsideClick={false}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>
            취소
          </Button>
          <Button onClick={onConfirm} loading={busy} disabled={!ready || uploading}>
            이행 확인
          </Button>
        </>
      }
    >
      <div className="space-y-5 py-1">
        <div>
          <p className="text-base font-semibold leading-snug text-slate-900">{target?.content}</p>
          {meta && <p className="mt-1 text-xs text-slate-500">{meta}</p>}
        </div>

        <section aria-label="증빙 사진">
          <h4 className="mb-2 text-xs font-bold tracking-wide text-slate-500">증빙 사진</h4>
          <input ref={fileRef} type="file" aria-label="증빙 사진 파일" accept="image/*" className="hidden"
            onChange={(e) => { onPhoto(e.target.files?.[0]); e.target.value = ""; }} />
          {!evidence?.evidenceUrl && !uploading && (
            <button type="button" onClick={() => fileRef.current?.click()}
              className="flex h-28 w-full flex-col items-center justify-center gap-1.5 rounded-lg border border-dashed border-slate-300 bg-slate-50 text-sm font-semibold text-slate-600 transition-colors hover:border-brand hover:text-brand">
              <Camera aria-hidden className="h-5 w-5" />
              사진 선택
            </button>
          )}
          {uploading && (
            <div aria-busy="true" className="flex h-28 items-center justify-center rounded-lg border border-slate-200 bg-slate-50 text-sm text-slate-500">
              <span className="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-brand" />
              사진 대조 중
            </div>
          )}
          {evidence?.evidenceUrl && !uploading && (
            <div className="grid gap-4 sm:grid-cols-[240px_minmax(0,1fr)]">
              <div>
                <img src={`${evidence.evidenceUrl}?v=${evidence.version}`} alt="증빙 사진"
                  className="aspect-[4/3] w-full rounded-lg border border-slate-200 object-cover" />
                <button type="button" onClick={() => fileRef.current?.click()}
                  className="mt-1.5 text-xs text-slate-500 underline-offset-2 hover:text-brand hover:underline">
                  다시 선택
                </button>
              </div>
              {evidence.photoCheck && evidence.photoCheck.items.length > 0 && (
                <ul aria-label="사진 대조" className="space-y-2.5">
                  {evidence.photoCheck.items.map((i) => <CheckRow key={i.item} item={i} />)}
                </ul>
              )}
            </div>
          )}
        </section>

        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="이행 내용" className="sm:col-span-2">
            <Input value={draft.resultNote} onChange={(e) => onChange({ resultNote: e.target.value })} placeholder={target?.content} />
          </FormField>
          <FormField label="확인자">
            <Input value={draft.verifiedBy} onChange={(e) => onChange({ verifiedBy: e.target.value })} placeholder="직책 이름" />
          </FormField>
          <div>
            <span className="mb-1 block text-xs font-semibold text-slate-500">개선 후 위험성</span>
            <SegmentedControl<RiskLevel>
              ariaLabel="개선 후 위험성"
              value={draft.residualLevel}
              onChange={(v) => onChange({ residualLevel: v })}
              options={LEVELS.map((l) => ({ value: l, label: LEVEL_LABEL[l], selectedClassName: riskColor(l).solid }))}
            />
          </div>
        </div>
        {target && target.relatedOpen > 0 && (
          <p className="text-sm text-slate-600">같은 위험요인의 미이행 대책 {target.relatedOpen}건도 함께 이행 확인됨</p>
        )}
        {high && (
          <p role="alert" className="rounded-md border border-risk-high-border bg-risk-high-bg px-3 py-2 text-sm text-risk-high-text">
            개선 후에도 상이면 추가 개선대책이 필요함
          </p>
        )}
      </div>
    </Modal>
  );
}
