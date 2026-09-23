// src/stores/useToastStore.ts
import { create } from 'zustand';
import { devtools } from 'zustand/middleware';

let toastSeq = 0;
const nextToastId = () => `toast-${++toastSeq}`;

export type ToastType = 'success' | 'error' | 'info' | 'warning';

/** 토스트 액션 버튼(D35 — export 완료 토스트의 "다운로드" 등) — label/onClick만 필요한 최소 형태. */
export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ToastMessage {
  id: string;
  type: ToastType;
  message: string;
  duration?: number;
  action?: ToastAction;
}

interface ToastState {
  toasts: ToastMessage[];
  addToast: (toast: Omit<ToastMessage, 'id'>) => void;
  removeToast: (id: string) => void;
  clearAll: () => void;
  success: (message: string, duration?: number) => void;
  error: (message: string, duration?: number) => void;
  info: (message: string, duration?: number) => void;
  warning: (message: string, duration?: number) => void;
  /** 액션 버튼이 붙은 success 토스트(예: export 완료 → 다운로드). 기본 노출시간을 길게(6s) 둔다. */
  successWithAction: (message: string, action: ToastAction, duration?: number) => void;
}

export const useToastStore = create<ToastState>()(
  devtools(
    (set) => ({
      toasts: [],
      addToast: (toast) => {
        // Generate simple unique ID
        const id = nextToastId();
        set((state) => ({ toasts: [...state.toasts, { ...toast, id }] }), false, 'toast/add');
      },
      removeToast: (id) =>
        set(
          (state) => ({
            toasts: state.toasts.filter((t) => t.id !== id),
          }),
          false,
          'toast/remove'
        ),
      clearAll: () => set({ toasts: [] }, false, 'toast/clearAll'),
      success: (message, duration = 3000) =>
        set(
          (state) => {
            const id = nextToastId();
            return { toasts: [...state.toasts, { id, type: 'success', message, duration }] };
          },
          false,
          'toast/success'
        ),
      error: (message, duration = 4000) =>
        set(
          (state) => {
            const id = nextToastId();
            return { toasts: [...state.toasts, { id, type: 'error', message, duration }] };
          },
          false,
          'toast/error'
        ),
      info: (message, duration = 3000) =>
        set(
          (state) => {
            const id = nextToastId();
            return { toasts: [...state.toasts, { id, type: 'info', message, duration }] };
          },
          false,
          'toast/info'
        ),
      warning: (message, duration = 4000) =>
        set(
          (state) => {
            const id = nextToastId();
            return { toasts: [...state.toasts, { id, type: 'warning', message, duration }] };
          },
          false,
          'toast/warning'
        ),
      successWithAction: (message, action, duration = 6000) =>
        set(
          (state) => {
            const id = nextToastId();
            return { toasts: [...state.toasts, { id, type: 'success', message, duration, action }] };
          },
          false,
          'toast/successWithAction'
        ),
    }),
    { name: 'ToastStore' }
  )
);
