import { formUrl } from "@/api/formUrl";
import AgentMessage from "@/components/common/AgentMessage";
import Button from "@/components/ui/Button";
import Modal from "@/components/ui/Modal";
import { StatusBadge } from "@/components/ui/Badge";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { WorkPlanDetail } from "@/types/workPlan";
import { formatDateTime } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

interface Props { detail: WorkPlanDetail | null; busy: boolean; onClose: () => void; onAcknowledge: (id: number) => void; onApprove: (id: number) => void }

export default function WorkPlanDetailModal({ detail, busy, onClose, onAcknowledge, onApprove }: Props) {
  if (!detail) return null;
  const footer = (
    <>
      <a className="inline-flex items-center rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50" href={formUrl.workPlan(detail.id)} target="_blank" rel="noreferrer">법정 서식</a>
      {!detail.briefingAckAt && detail.briefing && <Button size="sm" variant="secondary" loading={busy} onClick={() => onAcknowledge(detail.id)}>브리핑 확인 (TBM 기록)</Button>}
      {detail.status === "SUBMITTED" && <Button size="sm" loading={busy} onClick={() => onApprove(detail.id)}>승인</Button>}
    </>
  );
  return (
    <Modal isOpen onClose={onClose} title={`#${detail.id} ${detail.workName}`} footer={footer} maxWidth="2xl">
      <div className="flex flex-wrap items-center gap-2 text-sm text-slate-600">
        <StatusBadge tone={workPlanStatusTone(detail.status)}>{WORK_PLAN_STATUS_LABEL[detail.status]}</StatusBadge>
        <span>{detail.equipmentName ?? "설비 미상"}</span><span>·</span><span className="tabular-nums">{detail.workDate}</span>
      </div>
      {detail.briefingAckAt && <p className="mt-2 text-sm text-risk-low-text">브리핑 확인 {formatDateTime(detail.briefingAckAt)} — 상시평가 트랙의 TBM 증빙으로 보존됩니다.</p>}
      {detail.slots.length > 0 && (
        <table className="mt-4 w-full text-sm">
          <thead className="bg-slate-50 text-left text-xs text-slate-500"><tr><th className="px-3 py-2 font-semibold">확인 항목</th><th className="w-32 px-3 py-2 font-semibold">대장 기록</th><th className="w-32 px-3 py-2 font-semibold">작업자 확인</th></tr></thead>
          <tbody className="divide-y divide-slate-200">
            {detail.slots.map((s) => (
              <tr key={s.slotKey}>
                <td className="px-3 py-2">{s.question}</td>
                <td className="px-3 py-2">{s.ledgerValue ?? "-"}</td>
                <td className={s.conflicted ? "px-3 py-2 text-risk-high-text" : "px-3 py-2"}>{s.answeredValue ?? "-"}{s.conflicted && " (불일치)"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {detail.briefing && (
        <section className="mt-4 rounded-lg border border-slate-200 bg-slate-50 p-4">
          <h3 className="text-xs font-semibold text-slate-500">작업 전 브리핑</h3>
          <div className="mt-2 text-sm"><AgentMessage text={detail.briefing} /></div>
        </section>
      )}
    </Modal>
  );
}
