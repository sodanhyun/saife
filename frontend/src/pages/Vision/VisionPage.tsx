import { formUrl } from "@/api/formUrl";
import { StatusBadge } from "@/components/ui/Badge";
import Callout from "@/components/ui/Callout";
import LinkButton from "@/components/ui/LinkButton";
import LoadErrorCallout from "@/components/ui/LoadErrorCallout";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import Skeleton from "@/components/ui/Skeleton";
import CandidateCard from "@/pages/Vision/components/CandidateCard";
import EquipmentLinkBar from "@/pages/Vision/components/EquipmentLinkBar";
import InspectionBar from "@/pages/Vision/components/InspectionBar";
import PhotoPanel from "@/pages/Vision/components/PhotoPanel";
import RecentInspections from "@/pages/Vision/components/RecentInspections";
import { useVision } from "@/pages/Vision/hooks/useVision";
import { isComplete } from "@/pages/Vision/utils/inspection";
import VisionSkeleton from "@/pages/Vision/VisionSkeleton";

/** 현장 사진 분석(순회점검 기록). 사진 → 안전 문제와 예방 방법 → 설비 기록 연결 → 반영/제외 → 허용 가능 여부 → 개선대책 */
export default function VisionPage() {
  const v = useVision();
  const { result, error, analyzing, stage } = v.stream;
  if (v.loading) return <VisionSkeleton />;

  // 새로 찾은 위험을 먼저, 이미 아는 위험의 재확인은 뒤에 둔다(정렬은 안정적이라 판단해도 자리가 바뀌지 않는다)
  const candidates = [...(result?.candidates ?? [])].sort((a, b) => Number(a.alreadyKnown) - Number(b.alreadyKnown));
  const complete = result !== null && isComplete(result.participants, candidates);
  const started = v.preview !== null;

  return (
    <PageLayout>
      <PageHeader
        title="현장 사진 분석"
        actions={result && (
          <>
            <StatusBadge tone={complete ? "low" : "pending"}>{complete ? "확정" : "작성 중"}</StatusBadge>
            <LinkButton href={formUrl.assessment(result.assessmentId)} external>위험성평가표</LinkButton>
          </>
        )}
      />
      {v.loadError && <LoadErrorCallout className="mb-4" onRetry={v.refetch} />}

      {!started ? (
        <div className="mx-auto max-w-4xl space-y-6">
          <PhotoPanel preview={v.preview} analyzing={analyzing} stage={stage} onPick={v.pick} hero />
          <RecentInspections items={v.recent} />
        </div>
      ) : (
      <>
      <InspectionBar
        equipment={v.equipment}
        equipmentId={v.equipmentId}
        onEquipmentChange={v.setEquipmentId}
        equipmentLocked={analyzing || result !== null}
        inspector={v.inspector}
        onInspectorChange={v.setInspector}
        onInspectorCommit={v.commitInspector}
        participants={v.participants}
        onParticipantsChange={v.changeParticipants}
      />

      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
        <div className="min-w-0 lg:sticky lg:top-4">
          <PhotoPanel preview={v.preview} analyzing={analyzing} stage={stage} onPick={v.pick}
            marks={candidates.flatMap((c, i) => (c.box ? [{ no: i + 1, box: c.box, level: c.riskLevel, label: c.missingControl }] : []))} />
        </div>

        <section aria-label="위험요인" className="min-w-0 space-y-4">
          {error && <Callout tone="high">{error}</Callout>}


          {analyzing && (
            <div aria-hidden className="space-y-4">
              {[0, 1].map((i) => (
                <div key={i} className="rounded-xl border border-slate-200 bg-white p-5 shadow-card">
                  <div className="flex gap-4">
                    <Skeleton className="h-14 w-14" />
                    <div className="flex-1 space-y-2"><Skeleton className="h-4 w-32" /><Skeleton className="h-6 w-64" /><Skeleton className="h-10 w-full" /></div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {result?.scene && (
            <p className="rounded-xl border border-slate-200 bg-white px-5 py-3 text-sm text-slate-700 shadow-card animate-fade-in">
              <span className="mr-2 text-xs font-bold text-slate-500">사진 상황</span>{result.scene}
            </p>
          )}
          {result && result.equipmentId === null && candidates.length > 0 && (
            <EquipmentLinkBar key={result.assessmentId} equipment={v.equipment} photoEquipment={result.photoEquipment}
              suggestedId={result.suggestedEquipmentId} busy={v.assigning} onAssign={v.assignEquipment} />
          )}
          {result && (
            <SectionTitle>위험요인 {candidates.length}건</SectionTitle>
          )}
          {result && candidates.length === 0 && (
            <div className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center shadow-card">
              <p className="text-stage font-semibold text-slate-900">찾은 위험요인 없음</p>
            </div>
          )}
          {candidates.map((c, i) => (
            <CandidateCard
              key={c.hazardId}
              c={c}
              no={i + 1}
              delay={i * 120}
              busy={v.busyId === c.hazardId}
              onDecide={(id, reflect) => void v.decide(id, reflect)}
              onAcceptable={(id, ok) => void v.setAcceptable(id, ok)}
              onCreateAction={(cand, input) => void v.createAction(cand, input)}
            />
          ))}
        </section>
      </div>
      </>
      )}
    </PageLayout>
  );
}
