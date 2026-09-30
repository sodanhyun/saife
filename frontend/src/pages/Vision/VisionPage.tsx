import { formUrl } from "@/api/formUrl";
import Callout from "@/components/ui/Callout";
import EmptyState from "@/components/ui/EmptyState";
import KpiCell from "@/components/ui/KpiCell";
import LinkButton from "@/components/ui/LinkButton";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import CandidateCard from "@/pages/Vision/components/CandidateCard";
import UploadPanel from "@/pages/Vision/components/UploadPanel";
import { useVision } from "@/pages/Vision/hooks/useVision";
import VisionSkeleton from "@/pages/Vision/VisionSkeleton";

/** UC1 — 사진에서 빠진 안전조치를 찾는다. 사고유형 분류가 아니라 물리적으로 있거나 없는 것만 판정한다. */
export default function VisionPage() {
  const v = useVision();
  const { result, progress, error, analyzing } = v.stream;
  if (v.loading) return <VisionSkeleton />;
  return (
    <PageLayout>
      <PageHeader title="현장 사진 판독" description="사진에서 빠진 안전조치를 찾습니다. 물리적으로 있거나 없는 것만 판정합니다."
        actions={v.rate && <KpiCell className="min-w-[180px]" label={`후보 채택률${v.rate.axes?.length ? ` (${v.rate.axes.join("·")})` : ""}`} value={`${v.rate.adopted}/${v.rate.suggested}`} subText={v.rate.rate !== null ? `${Math.round(v.rate.rate * 100)}%` : v.rate.note} title={v.rate.note} />} />
      {v.loadError && <Callout tone="high" className="mb-4">데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.</Callout>}
      <div className="grid gap-5 lg:grid-cols-[360px_minmax(0,1fr)]">
        <UploadPanel equipment={v.equipment} equipmentId={v.equipmentId} onEquipmentChange={v.setEquipmentId} preview={v.preview} analyzing={analyzing} progress={progress} onPick={v.pick} />
        <div className="space-y-3">
          {analyzing && <Callout tone="progress" title={progress ?? "판독 중…"}>3~10초 걸립니다. 후보는 제안일 뿐이고 등급은 룰 엔진이 냅니다.</Callout>}
          {error && <Callout tone="high">{error}</Callout>}
          {result?.demoMode && <Callout tone="neutral">데모 모드입니다. API 키가 없어 모델 응답 대신 고정 픽스처를 표시하고 있습니다.</Callout>}
          {result && result.candidates.length === 0 && <EmptyState message="빠진 안전조치를 찾지 못했습니다" description="판정 대상 6축 중 이 사진에 해당하는 항목이 없습니다." />}
          {result?.candidates.map((c) => <CandidateCard key={c.hazardId} c={c} busy={v.busyId === c.hazardId} onDecide={(id, adopt) => void v.decide(id, adopt)} />)}
          {result && (
            <div className="flex flex-wrap gap-2">
              <LinkButton href={formUrl.assessment(result.assessmentId)} external>위험성평가표 (평가 #{result.assessmentId})</LinkButton>
              {/* 돌아오기 ③ — 후보를 하나라도 채택했고(§1-6 ③: "후보 채택 후"), 진입 컨텍스트로 설비가 정해져 있을 때만. 자동 이동은 하지 않는다. */}
              {v.equipmentId !== null && result.candidates.some((c) => c.adopted === true) && (
                <LinkButton href={`/equipment/${v.equipmentId}`}>이 설비 타임라인에 기록됨 → 보기</LinkButton>
              )}
            </div>
          )}
        </div>
      </div>
    </PageLayout>
  );
}
