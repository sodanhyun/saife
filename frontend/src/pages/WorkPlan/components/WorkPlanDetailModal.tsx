import { formUrl } from "@/api/formUrl";
import Button from "@/components/ui/Button";
import LinkButton from "@/components/ui/LinkButton";
import Modal from "@/components/ui/Modal";
import WorkPlanResultCard from "@/pages/WorkPlan/components/WorkPlanResultCard";
import type { WorkPlanAction } from "@/pages/WorkPlan/hooks/useWorkPlans";
import type { WorkPlanDetail } from "@/types/workPlan";

interface Props {
  detail: WorkPlanDetail | null;
  /** 진행 중인 액션. 누른 버튼만 loading, 나머지는 disabled로 중복 요청을 막는다 */
  busyAction: WorkPlanAction | null;
  onClose: () => void;
  onAcknowledge: (id: number) => void;
  onApprove: (id: number) => void;
}

/** 승인 화면. 제출 결과 카드와 같은 판정을 보고, 사람이 브리핑 확인과 승인을 누른다 */
export default function WorkPlanDetailModal({ detail, busyAction, onClose, onAcknowledge, onApprove }: Props) {
  if (!detail) return null;
  const busy = busyAction !== null;
  const actions = (
    <>
      {detail.status === "SUBMITTED" && <Button loading={busyAction === "approve"} disabled={busy} onClick={() => onApprove(detail.id)}>승인</Button>}
      {!detail.briefingAckAt && detail.briefing && (
        <Button variant="secondary" loading={busyAction === "ack"} disabled={busy} onClick={() => onAcknowledge(detail.id)}>작업자 브리핑 확인 (TBM 기록)</Button>
      )}
      <LinkButton href={formUrl.workPlan(detail.id)} external>법정 서식 보기</LinkButton>
      <p className="mr-auto self-center text-xs text-slate-500 order-first">확정과 승인은 사람이 합니다</p>
    </>
  );
  return (
    <Modal isOpen onClose={onClose} title="작업계획서 검토와 승인" maxWidth="4xl" footer={actions} className="[&>div:nth-child(2)]:p-0">
      <WorkPlanResultCard detail={detail} evidence={detail.evidence ?? []} variant="approval" />
    </Modal>
  );
}
