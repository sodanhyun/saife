// src/components/ui/InfoTooltip.tsx
import { useCallback, useRef, useState } from "react";

import { HelpCircle } from "lucide-react";
import { createPortal } from "react-dom";

import { cn } from "@/lib/cn";

interface InfoTooltipProps {
  text: string;
  /** 툴팁 방향 — "up"(기본) 또는 "down" */
  position?: "up" | "down";
  className?: string;
}

// 물음표(?) 아이콘 + 호버 시 설명 툴팁 — 폼 라벨/섹션 헤더 공통
// Portal + ref callback — overflow 컨테이너, 뷰포트 경계 모두 대응
export default function InfoTooltip({ text, position = "up", className }: InfoTooltipProps) {
  const iconRef = useRef<HTMLSpanElement>(null);
  const [visible, setVisible] = useState(false);
  const [style, setStyle] = useState<React.CSSProperties>({});

  // 툴팁 DOM이 마운트되면 위치를 측정하여 뷰포트 밖이면 보정
  const tooltipCallback = useCallback((el: HTMLSpanElement | null) => {
    if (!el || !iconRef.current) return;
    const icon = iconRef.current.getBoundingClientRect();
    const tt = el.getBoundingClientRect();
    const pad = 8;

    const top = position === "up" ? icon.top - 6 - tt.height : icon.bottom + 6;
    let left = icon.left + icon.width / 2 - tt.width / 2;

    // 뷰포트 좌우 클램핑
    if (left + tt.width > window.innerWidth - pad) {
      left = window.innerWidth - pad - tt.width;
    }
    if (left < pad) {
      left = pad;
    }

    setStyle({ top, left });
  }, [position]);

  return (
    <span
      ref={iconRef}
      className={cn("inline-flex items-center", className)}
      onMouseEnter={() => setVisible(true)}
      onMouseLeave={() => setVisible(false)}
    >
      <HelpCircle className="w-2.5 h-2.5 text-slate-300 cursor-help" />
      {visible && createPortal(
        <span
          ref={tooltipCallback}
          className="fixed w-max max-w-[240px] px-2.5 py-1.5 bg-slate-800 text-white text-xs leading-relaxed rounded-md shadow-modal whitespace-pre-line z-[9999] pointer-events-none"
          style={style}
        >
          {text}
        </span>,
        document.body
      )}
    </span>
  );
}
