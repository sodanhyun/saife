import { formUrl } from "@/api/formUrl";
import Callout from "@/components/ui/Callout";
import LinkButton from "@/components/ui/LinkButton";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";
import AdoptionRate from "@/pages/Vision/components/AdoptionRate";
import CandidateCard from "@/pages/Vision/components/CandidateCard";
import PhotoPanel from "@/pages/Vision/components/PhotoPanel";
import StepStrip from "@/pages/Vision/components/StepStrip";
import { useVision } from "@/pages/Vision/hooks/useVision";
import { deriveSteps } from "@/pages/Vision/utils/steps";
import VisionSkeleton from "@/pages/Vision/VisionSkeleton";

/** 사진을 올리기 전 오른쪽 자리. 이 화면이 무엇을 하는지 세 줄로 말한다 */
function Waiting() {
  const lines = [
    ["후보 판독", "모델이 사진에서 빠진 안전조치를 찾습니다. 사진에 보이는 것만 판정합니다"],
    ["사람의 채택", "AI 후보는 제안입니다. 채택한 것만 위험요인이 됩니다"],
    ["감소대책과 이행", "대책, 담당, 기한을 등록하고 이행 완료까지 같은 설비 기록에 남습니다"],
  ];
  return (
    <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-6">
      <p className="text-stage font-semibold text-slate-900">사진을 올리면 위험요인 후보가 여기에 뜹니다</p>
      <ol className="mt-4 space-y-3">
        {lines.map(([title, body], i) => (
          <li key={title} className="flex gap-3">
            <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full border-2 border-slate-300 text-xs font-bold text-slate-400">{i + 2}</span>
            <div>
              <p className="text-sm font-semibold text-slate-700">{title}</p>
              <p className="text-sm text-slate-500">{body}</p>
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

/** UC1 현장 사진 점검. 사진 → 후보 → 사람의 채택 → 감소대책 → 이행이 한 화면에서 닫힌다. */
export default function VisionPage() {
  const v = useVision();
  const { result, progress, error, analyzing, stage, stageLog } = v.stream;
  if (v.loading) return <VisionSkeleton />;

  const steps = deriveSteps({ hasPhoto: v.preview !== null, analyzing, result, progress });
  // 새로 찾은 위험을 먼저, 이미 아는 위험의 재확인은 뒤에 둔다(정렬은 안정적이라 판단해도 자리가 바뀌지 않는다)
  const candidates = [...(result?.candidates ?? [])].sort((a, b) => Number(a.alreadyKnown) - Number(b.alreadyKnown));

  return (
    <PageLayout>
      <PageHeader
        eyebrow="사진 점검"
        title="현장 사진 점검"
        description="사진에서 빠진 안전조치를 찾고, 사람이 채택한 위험요인만 감소대책과 이행까지 잇습니다."
        actions={v.rate && <AdoptionRate rate={v.rate} />}
      />
      {v.loadError && <Callout tone="high" className="mb-4">데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.</Callout>}

      <StepStrip steps={steps} />

      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
        <div className="min-w-0 space-y-4">
        <PhotoPanel
          equipment={v.equipment}
          equipmentId={v.equipmentId}
          onEquipmentChange={v.setEquipmentId}
          preview={v.preview}
          analyzing={analyzing}
          stage={stage}
          stageLog={stageLog}
          failed={error !== null}
          assessmentId={result?.assessmentId ?? null}
          onPick={v.pick}
        />
        {result && (
          <div className="flex items-center gap-4 rounded-xl border border-slate-200 bg-white px-5 py-4 shadow-card animate-fade-in">
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold text-slate-900">위험성평가표 (평가 #{result.assessmentId})</p>
              <p className="mt-0.5 text-xs text-slate-500">유해위험요인, 위험성 결정, 조치 내용 3요소. 시행규칙 제37조, 3년 보존</p>
            </div>
            <LinkButton href={formUrl.assessment(result.assessmentId)} external>서식 보기</LinkButton>
          </div>
        )}
        </div>

        <section aria-label="위험요인 후보" className="min-w-0 space-y-4">
          {error && <Callout tone="high">{error}</Callout>}
          {result?.demoMode && <Callout tone="neutral">데모 모드입니다. API 키가 없어 모델 응답 대신 고정 픽스처를 표시하고 있습니다.</Callout>}

          {!result && !analyzing && !error && <Waiting />}
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

          {result && (
            <div className="flex items-baseline justify-between">
              <h2 className="text-base font-semibold text-slate-900">위험요인 후보 {candidates.length}건</h2>
              <p className="text-xs text-slate-500">등급은 룰 엔진이 정합니다. 근거는 각 카드에서 펼칩니다</p>
            </div>
          )}
          {result && candidates.length === 0 && (
            <div className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center shadow-card">
              <p className="text-stage font-semibold text-slate-900">빠진 안전조치를 찾지 못했습니다</p>
              <p className="mt-1 text-sm text-slate-500">판정 대상 6축 중 이 사진에서 확인되는 항목이 없습니다.</p>
            </div>
          )}
          {candidates.map((c, i) => (
            <CandidateCard
              key={c.hazardId}
              c={c}
              delay={i * 120}
              busy={v.busyId === c.hazardId}
              onDecide={(id, adopt) => void v.decide(id, adopt)}
              onCreateAction={(cand, input) => void v.createAction(cand, input)}
              onCompleteAction={(hazardId, actionId) => void v.completeAction(hazardId, actionId)}
            />
          ))}

        </section>
      </div>
    </PageLayout>
  );
}
