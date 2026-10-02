// RecentInspections.tsx — 최근 순회점검. 행을 누르면 그 점검의 위험성평가표가 새 탭으로 열린다.
// 칩은 열마다 자리가 고정이라 행이 바뀌어도 세로로 줄이 맞는다.
import { ExternalLink } from "lucide-react";

import { formUrl } from "@/api/formUrl";
import { Badge, StatusBadge } from "@/components/ui/Badge";
import SectionTitle from "@/components/ui/SectionTitle";
import { shortDate } from "@/pages/Vision/utils/dates";
import type { RecentInspection } from "@/types/vision";

export default function RecentInspections({ items }: { items: RecentInspection[] }) {
  return (
    <section aria-label="최근 점검" className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-card">
      <div className="border-b border-slate-100 px-5 py-3.5">
        <SectionTitle>최근 점검</SectionTitle>
      </div>
      {items.length === 0 ? (
        <p className="px-5 py-8 text-center text-sm text-slate-400">기록 없음</p>
      ) : (
        <ul className="divide-y divide-slate-100">
          {items.map((r) => (
            <li key={r.assessmentId}>
              <a
                href={formUrl.assessment(r.assessmentId)}
                target="_blank"
                rel="noreferrer"
                aria-label={`${shortDate(r.assessedOn)} 점검 위험성평가표`}
                className="group grid grid-cols-[3.5rem_minmax(0,1fr)_5.5rem_3.5rem_4rem_1rem] items-center gap-3 px-5 py-3 transition-colors hover:bg-slate-50"
              >
                <span className="text-sm font-semibold tabular-nums text-slate-500">{shortDate(r.assessedOn)}</span>
                <span className="min-w-0">
                  <span className="block truncate text-sm font-semibold text-slate-900">{r.equipmentNames.length > 0 ? r.equipmentNames.join(", ") : "설비 지정 없음"}</span>
                  <span className="block truncate text-xs text-slate-500">
                    {[r.inspector, r.participants.length > 0 ? `참여 ${r.participants.length}명` : null].filter(Boolean).join(", ") || "-"}
                  </span>
                </span>
                <span className="flex justify-end"><Badge>위험요인 {r.hazardCount}</Badge></span>
                <span className="flex justify-end">{r.highCount > 0 && <StatusBadge tone="high">상 {r.highCount}</StatusBadge>}</span>
                <span className="flex justify-end">
                  <StatusBadge tone={r.complete ? "low" : "pending"}>{r.complete ? "확정" : "작성 중"}</StatusBadge>
                </span>
                <ExternalLink aria-hidden className="h-4 w-4 text-slate-300 transition-colors group-hover:text-slate-600" />
              </a>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
