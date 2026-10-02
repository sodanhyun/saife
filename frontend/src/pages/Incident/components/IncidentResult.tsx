// IncidentResult.tsx — 보고 결과: ① 사고(사실형 제목, 서식 버튼, 사고 전 기록) ② 후속 조치 3장 ③ 조사표 초안(접힘).
import DraftCard from "@/pages/Incident/components/DraftCard";
import FollowUpCards from "@/pages/Incident/components/FollowUpCards";
import IncidentHero from "@/pages/Incident/components/IncidentHero";
import type { IncidentRegisterResponse } from "@/types/incident";

export default function IncidentResult({ r }: { r: IncidentRegisterResponse }) {
  return (
    <div className="space-y-6">
      <IncidentHero r={r} />
      <FollowUpCards r={r} />
      <div className="animate-fade-in" style={{ animationDelay: "400ms" }}>
        <DraftCard r={r} />
      </div>
    </div>
  );
}
