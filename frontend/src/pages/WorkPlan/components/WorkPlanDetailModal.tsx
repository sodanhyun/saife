import { useState } from "react";

import { Link } from "react-router-dom";

import { formUrl } from "@/api/formUrl";
import Button from "@/components/ui/Button";
import { buttonClassName } from "@/components/ui/buttonStyles";
import Input from "@/components/ui/Input";
import LinkButton from "@/components/ui/LinkButton";
import Modal from "@/components/ui/Modal";
import Textarea from "@/components/ui/Textarea";
import WorkPlanResultCard from "@/pages/WorkPlan/components/WorkPlanResultCard";
import type { WorkPlanAction } from "@/pages/WorkPlan/hooks/useWorkPlans";
import { assessmentHref, isStopWork } from "@/pages/WorkPlan/utils/approval";
import type { WorkPlanDetail } from "@/types/workPlan";

interface Props {
  detail: WorkPlanDetail | null;
  /** 진행 중인 액션. 누른 버튼만 loading, 나머지는 disabled로 중복 요청을 막는다 */
  busyAction: WorkPlanAction | null;
  onClose: () => void;
  /** condition이 있으면 조건부 승인(잠정조치) */
  onApprove: (id: number, approver: string, condition?: string) => void;
  /** 잠정조치가 작업 금지면 승인 대신 보류 */
  onHold?: (id: number, reason: string) => void;
}

/** 모달 제목은 문서 종류 이름이다 */
function modalTitle(detail: WorkPlanDetail): string {
  return detail.documentType === "WORK_PLAN" ? "작업계획서" : "작업 전 안전점검표";
}

/**
 * 관리감독자 검토. 결과 카드와 같은 판정을 보고 승인한다. 승인 뒤에는 서식을 출력해 작업자 안내와 서명을 받는다.
 * 보류된 점검표는 승인 대신 수시평가로 간다.
 */
export default function WorkPlanDetailModal(props: Props) {
  if (!props.detail) return null;
  // 계획서가 바뀌면 잠정조치와 승인자 입력을 새로 시작한다
  return <DetailModalBody key={props.detail.id} {...props} detail={props.detail} />;
}

function DetailModalBody({ detail, busyAction, onClose, onApprove, onHold }: Props & { detail: WorkPlanDetail }) {
  const [interim, setInterim] = useState("");
  const [approver, setApprover] = useState(detail.supervisor ?? "");
  const busy = busyAction !== null;
  const pending = detail.status === "SUBMITTED";
  const held = detail.status === "HOLD";
  const interimRequired = pending && !!detail.briefingView?.interimRequired;
  const stopWork = interimRequired && isStopWork(interim);

  const actions = (
    <>
      <LinkButton href={formUrl.workPlan(detail.id)} external>서식 출력</LinkButton>
      {held && (
        <Link to={assessmentHref(detail.holdAssessmentId)} onClick={onClose} className={buttonClassName("primary", "md")}>수시평가</Link>
      )}
      {pending && (stopWork ? (
        <Button variant="danger" loading={busyAction === "hold"} disabled={busy || !onHold}
          onClick={() => onHold?.(detail.id, interim.trim())}>작업 보류</Button>
      ) : interimRequired ? (
        <Button loading={busyAction === "approve"} disabled={busy || !interim.trim()}
          onClick={() => onApprove(detail.id, approver.trim(), interim.trim())}>조건부 승인</Button>
      ) : (
        <Button loading={busyAction === "approve"} disabled={busy} onClick={() => onApprove(detail.id, approver.trim())}>승인</Button>
      ))}
    </>
  );

  return (
    <Modal isOpen onClose={onClose} title={modalTitle(detail)} maxWidth="4xl" footer={actions} className="[&>div:nth-child(2)]:p-0">
      <WorkPlanResultCard detail={detail} evidence={detail.evidence ?? []} variant="approval" />
      {pending && (
        <div className="grid gap-4 border-t border-slate-100 bg-panel px-6 py-4 sm:grid-cols-[200px_minmax(0,1fr)]">
          <div>
            <label htmlFor="approver" className="text-xs font-semibold text-slate-500">승인자 (관리감독자)</label>
            <Input id="approver" value={approver} onChange={(e) => setApprover(e.target.value)} className="mt-1.5 bg-white" />
          </div>
          {interimRequired && (
            <div>
              <label htmlFor="interim-measure" className="text-xs font-semibold text-pending-text">잠정조치</label>
              <Textarea id="interim-measure" rows={2} value={interim} onChange={(e) => setInterim(e.target.value)}
                placeholder="예: 2인 1조, 아웃트리거 고정, 맨 위 두 칸 사용 금지"
                className="mt-1.5 bg-white" />
              {stopWork && <p className="mt-1.5 text-sm font-medium text-risk-high-text">작업 금지는 승인이 아니라 작업 보류</p>}
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
