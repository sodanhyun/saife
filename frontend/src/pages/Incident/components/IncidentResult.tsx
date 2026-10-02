// IncidentResult.tsx — 등록 결과. ① 예고 히어로(사고 전 기록) ② 연쇄 4단계 사슬 ③ 단계별 상세 3장 ④ 조사표 초안(접힘).
import AffectedWorkPlans from "@/pages/Incident/components/AffectedWorkPlans";
import CascadeList, { STEP_INTERVAL_MS } from "@/pages/Incident/components/CascadeList";
import DraftCard from "@/pages/Incident/components/DraftCard";
import FollowUpCard from "@/pages/Incident/components/FollowUpCard";
import PremonitionHero from "@/pages/Incident/components/PremonitionHero";
import ReportDutyCard from "@/pages/Incident/components/ReportDutyCard";
import { CASCADE_ANCHOR_BY_KIND } from "@/pages/Incident/utils/cascadeAnchor";
import { buildCascadeCards } from "@/pages/Incident/utils/premonition";
import type { IncidentRegisterResponse } from "@/types/incident";

export default function IncidentResult({ r }: { r: IncidentRegisterResponse }) {
  const cards = buildCascadeCards(r);
  // 상세는 사슬이 다 뜬 뒤에 들어온다
  const detailDelay = 500 + cards.length * STEP_INTERVAL_MS;

  return (
    <div className="space-y-6">
      <div id={CASCADE_ANCHOR_BY_KIND.RECALL} className="scroll-mt-6">
        <PremonitionHero r={r} />
      </div>

      <CascadeList cards={cards} steps={r.cascade ?? []} />

      <div className="grid items-stretch gap-4 lg:grid-cols-3 animate-rise-in" style={{ animationDelay: `${detailDelay}ms` }}>
        <FollowUpCard id={CASCADE_ANCHOR_BY_KIND.FOLLOW_UP} followUp={r.followUp} />
        <ReportDutyCard id={CASCADE_ANCHOR_BY_KIND.REPORT} duty={r.reportDuty} incidentId={r.incident.id} />
        <AffectedWorkPlans id={CASCADE_ANCHOR_BY_KIND.WORK_PLAN} workPlans={r.affectedWorkPlans ?? []} />
      </div>

      <div className="animate-fade-in" style={{ animationDelay: `${detailDelay + 200}ms` }}>
        <DraftCard r={r} />
      </div>
    </div>
  );
}
