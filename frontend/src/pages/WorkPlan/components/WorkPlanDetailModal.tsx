import { useState } from "react";

import { formUrl } from "@/api/formUrl";
import Button from "@/components/ui/Button";
import LinkButton from "@/components/ui/LinkButton";
import Modal from "@/components/ui/Modal";
import Textarea from "@/components/ui/Textarea";
import WorkPlanResultCard from "@/pages/WorkPlan/components/WorkPlanResultCard";
import { formatShortDateTime } from "@/pages/WorkPlan/utils/format";
import type { WorkPlanAction } from "@/pages/WorkPlan/hooks/useWorkPlans";
import type { WorkPlanDetail } from "@/types/workPlan";

interface Props {
  detail: WorkPlanDetail | null;
  /** 진행 중인 액션. 누른 버튼만 loading, 나머지는 disabled로 중복 요청을 막는다 */
  busyAction: WorkPlanAction | null;
  onClose: () => void;
  onAcknowledge: (id: number) => void;
  /** condition이 있으면 조건부 승인(잠정조치) */
  onApprove: (id: number, condition?: string) => void;
}

/** 관리감독자 검토. 결과 카드와 같은 판정을 보고 TBM 실시 확인과 승인을 한다 */
export default function WorkPlanDetailModal({ detail, busyAction, onClose, onAcknowledge, onApprove }: Props) {
  if (!detail) return null;
  // 계획서가 바뀌면 잠정조치 입력을 새로 시작한다
  return <DetailModalBody key={detail.id} detail={detail} busyAction={busyAction} onClose={onClose} onAcknowledge={onAcknowledge} onApprove={onApprove} />;
}

function DetailModalBody({ detail, busyAction, onClose, onAcknowledge, onApprove }: Props & { detail: WorkPlanDetail }) {
  const [interim, setInterim] = useState("");
  const busy = busyAction !== null;
  const pending = detail.status === "SUBMITTED";
  const interimRequired = pending && !!detail.briefingView?.interimRequired;

  const actions = (
    <>
      {!detail.briefingAckAt && detail.briefing && (
        <Button variant="secondary" loading={busyAction === "ack"} disabled={busy} onClick={() => onAcknowledge(detail.id)}>TBM 실시 확인</Button>
      )}
      <LinkButton href={formUrl.workPlan(detail.id)} external>서식 출력</LinkButton>
      {pending && (interimRequired ? (
        <Button loading={busyAction === "approve"} disabled={busy || !interim.trim()} onClick={() => onApprove(detail.id, interim.trim())}>조건부 승인</Button>
      ) : (
        <Button loading={busyAction === "approve"} disabled={busy} onClick={() => onApprove(detail.id)}>승인</Button>
      ))}
    </>
  );

  return (
    <Modal isOpen onClose={onClose} title="작업 전 안전점검표" maxWidth="4xl" footer={actions} className="[&>div:nth-child(2)]:p-0">
      <WorkPlanResultCard detail={detail} evidence={detail.evidence ?? []} variant="approval" />
      {interimRequired && (
        <div className="border-t border-slate-100 bg-pending-bg px-6 py-4">
          <label htmlFor="interim-measure" className="text-sm font-semibold text-pending-text">잠정조치</label>
          <Textarea id="interim-measure" rows={2} value={interim} onChange={(e) => setInterim(e.target.value)}
            placeholder="예: 이동식 비계 설치 전까지 사다리 작업 금지, 2인 1조로 사다리 고정"
            className="mt-1.5 bg-white" />
        </div>
      )}
      {detail.briefingAckAt && (
        <p className="border-t border-slate-100 px-6 py-3 text-sm text-slate-600">TBM 실시 {formatShortDateTime(detail.briefingAckAt)}</p>
      )}
    </Modal>
  );
}
