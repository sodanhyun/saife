// IncidentResult.tsx — 보고 결과: ① 사고(사실형 제목, 서식 버튼, 사고 전 기록) ② 후속 조치 3장 ③ 조사표 초안(접힘).
import DraftCard from "@/pages/Incident/components/DraftCard";
import FollowUpCards from "@/pages/Incident/components/FollowUpCards";
import IncidentHero from "@/pages/Incident/components/IncidentHero";
import { reportRequired } from "@/pages/Incident/utils/priorRecord";
import type { IncidentRegisterResponse } from "@/types/incident";

interface Props {
  r: IncidentRegisterResponse;
  /** 조사표 제출 완료 처리 */
  onMarkSubmitted?: () => void;
  submitting?: boolean;
}

export default function IncidentResult({ r, onMarkSubmitted, submitting = false }: Props) {
  // 조사표 초안은 제출 의무가 있는 재해에만 둔다. 아차사고와 제출 대상이 아닌 재해는 서식 초안이 없다
  const showDraft = r.incident.severity !== "NEAR_MISS" && (reportRequired(r.reportDuty) || r.reportDuty.status === "SUBMITTED");
  return (
    <div className="space-y-6">
      <IncidentHero r={r} />
      <FollowUpCards r={r} onMarkSubmitted={onMarkSubmitted} submitting={submitting} />
      {showDraft && (
        <div className="animate-fade-in" style={{ animationDelay: "400ms" }}>
          <DraftCard r={r} />
        </div>
      )}
    </div>
  );
}
