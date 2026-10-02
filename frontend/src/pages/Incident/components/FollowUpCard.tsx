// FollowUpCard.tsx — 사고 연쇄 2단계 상세. 자동 생성된 수시평가의 재판정 결과와 룰 근거.
import { formUrl } from "@/api/formUrl";
import { Badge } from "@/components/ui/Badge";
import LinkButton from "@/components/ui/LinkButton";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import { DetailCard } from "@/pages/Incident/components/DetailCard";
import { gradeTo, plain } from "@/pages/Incident/utils/premonition";
import { ACCIDENT_LABEL, RISK_LABEL } from "@/types/domain";
import type { FollowUpView } from "@/types/incident";

export default function FollowUpCard({ followUp, id }: { followUp: FollowUpView; id?: string }) {
  return (
    <DetailCard
      id={id}
      label="수시평가 자동 생성"
      title={followUp.assessmentId ? `수시평가 #${followUp.assessmentId}, 초안` : "수시평가 생성 안 됨"}
      note={plain(followUp.legalBasis)}
      actions={
        followUp.assessmentId ? (
          <LinkButton href={formUrl.assessment(followUp.assessmentId)} external>위험성평가표</LinkButton>
        ) : undefined
      }
    >
      {followUp.regraded.length === 0 ? (
        <p className="text-sm text-slate-500">재평가 대상 위험요인이 없습니다.</p>
      ) : (
        <ul className="space-y-4">
          {followUp.regraded.map((g) => (
            <li key={g.hazardId} className="flex items-start gap-3">
              <RiskGradeMark level={g.after} />
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-1.5 text-sm font-semibold text-slate-900">
                  {g.accidentType && <Badge>{ACCIDENT_LABEL[g.accidentType]}</Badge>}
                  <span>{g.missingControl ?? "사고로 확인된 위험요인"}</span>
                </p>
                <p className="mt-0.5 text-xs text-slate-500">
                  {g.before === null
                    ? "신규 등록"
                    : g.changed
                      ? `종전 '${RISK_LABEL[g.before]}'에서 ${gradeTo(g.after)} 변경`
                      : `종전 '${RISK_LABEL[g.before]}' 유지`}
                </p>
                <p className="mt-1 rounded-md bg-panel px-2 py-1 font-mono text-xs leading-relaxed text-slate-600">{plain(g.ruleTrace)}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </DetailCard>
  );
}
