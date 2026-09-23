// src/hooks/useConfirm.ts
import { useState, useCallback } from "react";

export interface ConfirmState {
  isOpen: boolean;
  title: string;
  message: React.ReactNode;
  onConfirm: () => void;
  isDestructive?: boolean;
}

const CONFIRM_INITIAL: ConfirmState = {
  isOpen: false, title: "", message: "", onConfirm: () => {},
};

/**
 * 확인 모달 상태 관리 공통 훅.
 * ConfirmModal 컴포넌트와 함께 사용한다.
 */
export function useConfirm() {
  const [confirmState, setConfirmState] = useState<ConfirmState>(CONFIRM_INITIAL);

  const requestConfirm = useCallback(
    (message: React.ReactNode, onConfirm: () => void, title = "확인", isDestructive = false) => {
      setConfirmState({ isOpen: true, title, message, onConfirm, isDestructive });
    },
    []
  );

  const closeConfirm = useCallback(() => setConfirmState(CONFIRM_INITIAL), []);

  return { confirmState, requestConfirm, closeConfirm };
}
