// src/components/ui/ToastContainer.tsx
import { useCallback, useEffect, useState } from "react";

import cn from "@/lib/cn";
import type { ToastMessage, ToastType } from "@/stores/useToastStore";
import { useToastStore } from "@/stores/useToastStore";

/** 최대 표시 토스트 수 */
const MAX_VISIBLE = 3;

const STYLE_MAP: Record<ToastType, { bg: string; icon: string }> = {
  success: { bg: "bg-risk-low", icon: "M5 13l4 4L19 7" },
  error: { bg: "bg-risk-high", icon: "M6 18L18 6M6 6l12 12" },
  // 경고삼각 대신 info와 같은 원형 아이콘 계열로 통일 — MES 톤(장식 아이콘 배제).
  warning: { bg: "bg-pending", icon: "M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" },
  info: { bg: "bg-slate-700", icon: "M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" },
};

/** 퇴장 중인 토스트 ID를 추적. 콜백은 안정 참조여야 한다 — ToastItem 타이머 effect의 의존성이다. */
function useExitingIds() {
  const [exitingIds, setExitingIds] = useState<Set<string>>(new Set());

  const markExiting = useCallback(
    (id: string) => setExitingIds((prev) => new Set(prev).add(id)),
    [],
  );

  const clearExiting = useCallback(
    (id: string) =>
      setExitingIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      }),
    [],
  );

  return { exitingIds, markExiting, clearExiting };
}

function ToastItem({
  toast,
  index,
  isExiting,
  onStartExit,
}: {
  toast: ToastMessage;
  /** 스택 내 위치 (0 = 최상단/최신) */
  index: number;
  isExiting: boolean;
  /** 안정 참조(useCallback)로 받는다 — 매 렌더 새 클로저면 자동 제거 타이머가 리셋된다 */
  onStartExit: (id: string) => void;
}) {
  const removeToast = useToastStore((s) => s.removeToast);
  const { id, type, message, duration, action } = toast;
  const style = STYLE_MAP[type];

  // 자동 제거 타이머
  useEffect(() => {
    if (!duration) return;
    const timer = setTimeout(() => onStartExit(id), duration);
    return () => clearTimeout(timer);
  }, [id, duration, onStartExit]);

  // 퇴장 애니메이션 후 실제 제거
  useEffect(() => {
    if (!isExiting) return;
    const timer = setTimeout(() => removeToast(id), 250);
    return () => clearTimeout(timer);
  }, [isExiting, id, removeToast]);

  // 스택 오프셋: 최신(0)은 정위치, 오래된 것일수록 뒤로 밀림
  const stackOffset = index * 6;
  const stackScale = 1 - index * 0.04;
  const stackOpacity = index === 0 ? 1 : index === 1 ? 0.7 : 0.4;

  return (
    <div
      className={cn(
        "absolute top-0 right-0 w-full max-w-[340px] pointer-events-auto",
        "transition-all duration-300 ease-out",
        isExiting && "opacity-0 -translate-y-2"
      )}
      style={{
        transform: `translateY(${stackOffset}px) scale(${stackScale})`,
        opacity: isExiting ? 0 : stackOpacity,
        zIndex: 100 - index,
      }}
      role="alert"
    >
      <div
        className={cn(
          "flex items-center gap-2.5 px-4 py-2.5 rounded-lg shadow-toast text-white text-sm leading-snug cursor-pointer",
          style.bg
        )}
        onClick={() => onStartExit(id)}
      >
        <svg
          className="w-4 h-4 shrink-0 opacity-90"
          fill="none"
          stroke="currentColor"
          strokeWidth={2.2}
          strokeLinecap="round"
          strokeLinejoin="round"
          viewBox="0 0 24 24"
        >
          <path d={style.icon} />
        </svg>
        <span className="flex-1 break-words">{message}</span>
        {action && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              action.onClick();
            }}
            className="shrink-0 whitespace-nowrap rounded-md bg-white/15 px-2 py-1 text-xs font-semibold hover:bg-white/25 transition-colors"
          >
            {action.label}
          </button>
        )}
      </div>
    </div>
  );
}

export default function ToastContainer() {
  const toasts = useToastStore((s) => s.toasts);
  const { exitingIds, markExiting, clearExiting } = useExitingIds();

  // 퇴장 완료된 ID 정리
  useEffect(() => {
    exitingIds.forEach((id) => {
      if (!toasts.some((t) => t.id === id)) clearExiting(id);
    });
  }, [toasts, exitingIds, clearExiting]);

  if (toasts.length === 0) return null;

  // 최신 토스트가 맨 위 (배열 끝 = 최신)
  const reversed = [...toasts].reverse();
  const visible = reversed.slice(0, MAX_VISIBLE);
  const hiddenCount = Math.max(0, toasts.length - MAX_VISIBLE);

  // 스택 전체 높이: 첫 토스트 + 오프셋들
  const stackHeight = 44 + (visible.length - 1) * 6;

  return (
    <div
      className="fixed top-5 right-5 z-[9999] pointer-events-none"
      style={{ width: 340, height: stackHeight }}
    >
      {visible.map((toast, index) => (
        <ToastItem
          key={toast.id}
          toast={toast}
          index={index}
          isExiting={exitingIds.has(toast.id)}
          onStartExit={markExiting}
        />
      ))}

      {/* 숨겨진 토스트 카운트 */}
      {hiddenCount > 0 && (
        <div
          className="absolute right-0 text-xs text-slate-400 font-medium pointer-events-none text-right w-full"
          style={{ top: stackHeight + 4 }}
        >
          {`+${hiddenCount}개 더`}
        </div>
      )}
    </div>
  );
}
