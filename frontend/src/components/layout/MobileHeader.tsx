// src/components/layout/MobileHeader.tsx
import { Menu } from "lucide-react";

import { useNavigate } from "react-router-dom";

import { LANDING_PATH } from "@/components/layout/menu";

interface MobileHeaderProps {
  onOpenSidebar: () => void;
}

// 모바일 헤더 — 텍스트 브랜드(SAIFE), 로고 이미지 없음
export default function MobileHeader({ onOpenSidebar }: MobileHeaderProps) {
  const navigate = useNavigate();

  return (
    <header className="lg:hidden flex items-center justify-between h-14 px-4 bg-slate-950 border-b border-slate-800/60 sticky top-0 z-50">
      <button
        onClick={onOpenSidebar}
        className="p-2 text-white hover:bg-white/10 rounded-md transition-colors"
      >
        <Menu size={24} />
      </button>

      <button
        onClick={() => navigate(LANDING_PATH)}
        className="flex flex-col items-center cursor-pointer hover:opacity-80 transition-opacity"
      >
        <span className="text-white font-bold text-base tracking-tight leading-tight">SAIFE</span>
      </button>

      <div className="w-10" /> {/* 가운데 정렬용 스페이서 */}
    </header>
  );
}
