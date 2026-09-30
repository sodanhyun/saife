// PhotoLightbox.tsx — 사진 카드 클릭 시 원본 크게 보기. 기존 Modal을 재사용(ESC·포커스는 Modal이 담당).
import Modal from "@/components/ui/Modal";
import type { Evidence } from "@/types/evidence";

export default function PhotoLightbox({ e, onClose }: { e: Evidence; onClose: () => void }) {
  return (
    <Modal isOpen onClose={onClose} title={e.title} maxWidth="lg">
      <img src={e.mediaUrl ?? undefined} alt={e.title} className="max-h-[70vh] w-full rounded object-contain" />
      <p className="mt-2 text-xs text-slate-500">출처: 한국산업안전보건공단 사고사망 게시판 · 근거 #{e.no}</p>
    </Modal>
  );
}
