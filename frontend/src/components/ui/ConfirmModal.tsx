// src/components/ui/ConfirmModal.tsx
import Button from "@/components/ui/Button";
import Modal from "@/components/ui/Modal";

type ConfirmVariant = "destructive" | "primary";

interface ConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  message: React.ReactNode;
  confirmText?: string;
  cancelText?: string;
  /** @deprecated variant 사용 */
  isDestructive?: boolean;
  variant?: ConfirmVariant;
}

/** 확인 모달 — useConfirm 훅과 짝으로 쓴다. Enter=확인, ESC=취소는 Modal이 처리한다. */
export default function ConfirmModal({
  isOpen, onClose, onConfirm, title, message,
  confirmText = "확인", cancelText = "취소", isDestructive = true, variant,
}: ConfirmModalProps) {
  const resolved = variant ?? (isDestructive ? "destructive" : "primary");
  const handleConfirm = () => {
    onConfirm();
    onClose();
  };
  const footer = (
    <>
      <Button variant="secondary" onClick={onClose}>{cancelText}</Button>
      <Button variant={resolved === "destructive" ? "danger" : "primary"} onClick={handleConfirm}>
        {confirmText}
      </Button>
    </>
  );
  return (
    <Modal isOpen={isOpen} onClose={onClose} onConfirm={handleConfirm} title={title} footer={footer} maxWidth="sm">
      <div className="py-2 text-sm leading-relaxed text-slate-600">{message}</div>
    </Modal>
  );
}
