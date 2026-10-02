// PhotoLightbox.tsx — 사진 카드 클릭 시 원본 크게 보기. 기존 Modal을 재사용(ESC·포커스는 Modal이 담당).
import { cleanEvidenceTitle } from "@/components/evidence/evidenceMeta";
import Modal from "@/components/ui/Modal";
import type { Evidence } from "@/types/evidence";

/** 원문 머리표 중 "[4/17, 서울 성북구]"처럼 날짜와 지역이 든 것 */
function whenWhere(title: string): string | null {
  const m = title.match(/\[(\d{1,2}\/\d{1,2},\s*[^\]]+)\]/);
  return m ? m[1].replace(/,\s*/, ", ") : null;
}

export default function PhotoLightbox({ e, onClose }: { e: Evidence; onClose: () => void }) {
  const title = cleanEvidenceTitle(e.title);
  const meta = whenWhere(e.title);
  return (
    <Modal isOpen onClose={onClose} title={title} maxWidth="lg">
      <img src={e.mediaUrl ?? undefined} alt={title} className="max-h-[70vh] w-full rounded object-contain" />
      <p className="mt-2 text-xs text-slate-500">
        {meta && <span className="mr-2">{meta}</span>}
        출처: 한국산업안전보건공단 사고사망 게시판
      </p>
    </Modal>
  );
}
