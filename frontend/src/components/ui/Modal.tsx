// src/components/ui/Modal.tsx
import React, { useEffect, useRef } from "react";
import { createPortal } from "react-dom";

import { X } from "lucide-react";

import { cn } from "@/lib/cn";
import { useFormKeyboardNav } from "@/hooks/useFormKeyboardNav";

export interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm?: () => void;
  title?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  maxWidth?: "sm" | "md" | "lg" | "xl" | "2xl" | "full";
  closeOnOutsideClick?: boolean;
  /** 모달 패널에 추가 클래스 (max-h 오버라이드 등) */
  className?: string;
}

export default function Modal({
  isOpen,
  onClose,
  onConfirm,
  title,
  children,
  footer,
  maxWidth = "md",
  closeOnOutsideClick = true,
  className,
}: ModalProps) {
  const modalRef = useRef<HTMLDivElement>(null);

  // 전역 키보드 표준 — 열릴 때 패널 내 첫 input 자동 포커스 + 화살표 위/아래 이동.
  // Enter 저장·ESC 닫기는 아래 자체 핸들러가 담당하므로 handleEnter=false로 중복을 방지한다.
  useFormKeyboardNav({ containerRef: modalRef, enabled: isOpen, handleEnter: false });

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (!isOpen) return;
      if (e.key === "Escape") {
        onClose();
      } else if (e.key === "Enter" && onConfirm) {
        const tag = (e.target as HTMLElement)?.tagName;
        if (tag === "TEXTAREA") return;
        e.preventDefault();
        onConfirm();
      }
    };
    if (isOpen) {
      document.addEventListener("keydown", handleKeyDown);
      document.body.style.overflow = "hidden";
    } else {
      document.body.style.overflow = "unset";
    }
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = "unset";
    };
  }, [isOpen, onClose, onConfirm]);

  if (!isOpen) return null;

  const handleBackdropClick = (e: React.MouseEvent) => {
    if (closeOnOutsideClick && e.target === e.currentTarget) {
      onClose();
    }
  };

  const maxWidthClass = {
    sm: "sm:max-w-sm",
    md: "sm:max-w-md",
    lg: "sm:max-w-lg",
    xl: "sm:max-w-xl",
    "2xl": "sm:max-w-2xl",
    full: "sm:max-w-[90vw]",
  }[maxWidth];

  return createPortal(
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center p-2 sm:p-4 bg-black/40 backdrop-blur-[6px] transition-opacity"
      onMouseDown={handleBackdropClick}
    >
      <div
        ref={modalRef}
        className={cn(
          "bg-white w-full overflow-hidden flex flex-col",
          "rounded-xl",
          "shadow-modal",
          "ring-1 ring-black/[0.06]",
          "max-h-[80vh] sm:max-h-[88vh]",
          maxWidthClass,
          className,
        )}
      >
        {/* Header */}
        {title && (
          <div className="flex items-center justify-between gap-3 px-4 py-2.5 sm:px-5 sm:py-3 border-b border-slate-100/80 bg-slate-50/50">
            <h3 className="text-base font-bold text-slate-900 truncate">
              {title}
            </h3>
            <button
              onClick={onClose}
              className="flex-shrink-0 w-7 h-7 flex items-center justify-center rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-all"
            >
              <X size={16} strokeWidth={2.5} />
            </button>
          </div>
        )}

        {/* Body */}
        <div className="overflow-y-auto flex-1 px-3 py-2 sm:px-5 sm:py-3">
          {children}
        </div>

        {/* Footer */}
        {footer && (
          <div className="px-4 py-2.5 sm:px-5 sm:py-3 bg-slate-50/60 border-t border-slate-100/80 flex justify-end gap-2">
            {footer}
          </div>
        )}
      </div>
    </div>,
    document.body
  );
}
