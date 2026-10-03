// CompleteActionModal.tsx: 이행 완료 확인. 대책 내용만 보이고 취소, 이행 완료 두 버튼
import Button from "@/components/ui/Button";
import Modal from "@/components/ui/Modal";
import type { ActionListItem } from "@/types/action";

interface Props {
  target: ActionListItem | null;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export default function CompleteActionModal({ target, busy, onCancel, onConfirm }: Props) {
  return (
    <Modal
      isOpen={target !== null}
      onClose={onCancel}
      onConfirm={busy ? undefined : onConfirm}
      title="이행 완료"
      maxWidth="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>
            취소
          </Button>
          <Button onClick={onConfirm} loading={busy}>
            이행 완료
          </Button>
        </>
      }
    >
      <p className="py-2 text-sm font-semibold leading-relaxed text-slate-900">{target?.content}</p>
      {target?.equipmentName && <p className="pb-2 text-xs text-slate-500">{target.equipmentName}</p>}
    </Modal>
  );
}
