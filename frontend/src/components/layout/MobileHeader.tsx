// src/components/layout/MobileHeader.tsx
import { Menu } from "lucide-react";

import { useNavigate } from "react-router-dom";

import { LANDING_PATH } from "@/components/layout/menu";

interface MobileHeaderProps {
  onOpenSidebar: () => void;
}

// 모바일 헤더: 로고 이미지
export default function MobileHeader({ onOpenSidebar }: MobileHeaderProps) {
  const navigate = useNavigate();

  return (
    <header className="lg:hidden flex items-center justify-between h-14 px-4 bg-brand-ink border-b border-white/10 sticky top-0 z-50">
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
        <img src="/brand/saife-logo-inverse.svg" alt="SAIFE" className="h-7" />
      </button>

      <div className="w-10" /> {/* 가운데 정렬용 스페이서 */}
    </header>
  );
}
