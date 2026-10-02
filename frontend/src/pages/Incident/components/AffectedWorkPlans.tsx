// AffectedWorkPlans.tsx — 사고 연쇄 4단계(WORK_PLAN)의 상세. 같은 설비의 진행 중 작업계획서에 붙은 경고를 보인다.
import { StatusBadge } from "@/components/ui/Badge";
import { DetailCard } from "@/pages/Incident/components/DetailCard";
import { plain } from "@/pages/Incident/utils/premonition";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { AffectedWorkPlan } from "@/types/incident";
import { formatDate } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

export default function AffectedWorkPlans({ workPlans, id }: { workPlans: AffectedWorkPlan[]; id?: string }) {
  // 같은 사고가 붙인 경고는 문구가 같다. 한 번만 크게 보이고, 계획서마다 반복하지 않는다
  const warnings = [...new Set(workPlans.map((p) => plain(p.warning)))];
  return (
    <DetailCard id={id} label="작업계획서 경고" title={`진행 중 ${workPlans.length}건에 부착`}>
      {workPlans.length === 0 ? (
        <p className="text-sm text-slate-500">같은 설비에 진행 중인 작업계획서가 없습니다.</p>
      ) : (
        <>
          {warnings.map((w) => (
            <p key={w} className="rounded-md border border-risk-high-border bg-risk-high-bg px-3 py-2 text-sm font-medium text-risk-high-text">
              {w}
            </p>
          ))}
          <ul className="mt-3 divide-y divide-slate-100">
            {workPlans.map((p) => (
              <li key={p.workPlanId} className="flex items-center gap-3 py-2 text-sm">
                <span className="w-10 shrink-0 text-xs tabular-nums text-slate-400">#{p.workPlanId}</span>
                <span className="min-w-0 flex-1 truncate text-slate-800">{p.workName}</span>
                <span className="shrink-0 text-xs tabular-nums text-slate-500">{formatDate(p.workDate)}</span>
                <StatusBadge tone={workPlanStatusTone(p.status)}>{WORK_PLAN_STATUS_LABEL[p.status]}</StatusBadge>
              </li>
            ))}
          </ul>
        </>
      )}
    </DetailCard>
  );
}
