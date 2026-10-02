import Button from "@/components/ui/Button";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import Skeleton from "@/components/ui/Skeleton";
import IncidentForm from "@/pages/Incident/components/IncidentForm";
import IncidentProcessing from "@/pages/Incident/components/IncidentProcessing";
import IncidentResult from "@/pages/Incident/components/IncidentResult";
import IncidentTable from "@/pages/Incident/components/IncidentTable";
import LoadError from "@/pages/Incident/components/LoadError";
import { useIncident } from "@/pages/Incident/hooks/useIncident";
import IncidentSkeleton from "@/pages/Incident/IncidentSkeleton";

/** UC2 사고 보고. 보고 한 번으로 사고 전 기록, 수시평가, 조사표 기한, 작업 보류가 이어진다. 결과가 나오면 폼은 물러난다. */
export default function IncidentPage() {
  const s = useIncident();
  if (s.loading) return <IncidentSkeleton />;
  // 이력 행을 눌러 다른 사고를 여는 중이면 결과 자리에 골격을 둔다(지금 결과를 그대로 두면 눌린 줄 모른다)
  const openingOther = s.opening !== null && s.opening !== s.response?.incident.id;
  return (
    <PageLayout>
      <PageHeader
        title="사고 보고"
        actions={
          s.response ? (
            <Button variant="secondary" onClick={s.reset}>
              새 사고 보고
            </Button>
          ) : undefined
        }
      />
      <div className="space-y-6">
        {s.formLoadError && !s.response && <LoadError onRetry={() => window.location.reload()} />}
        {!s.response && !openingOther && (
          <IncidentForm
            form={s.form}
            setForm={s.setForm}
            equipment={s.equipment}
            plans={s.plans}
            busy={s.busy}
            error={s.error}
            fieldErrors={s.fieldErrors}
            maxOccurredAt={s.maxOccurredAt}
            onSubmit={() => void s.submit()}
          />
        )}
        {s.busy && <IncidentProcessing />}
        {openingOther ? (
          <div role="status" aria-label="사고 불러오는 중" className="space-y-4">
            <Skeleton className="h-64 w-full" />
            <Skeleton className="h-40 w-full" />
          </div>
        ) : (
          s.response && (
            <IncidentResult
              key={s.response.incident.id}
              r={s.response}
              onMarkSubmitted={() => void s.markSubmitted()}
              submitting={s.submitting}
            />
          )
        )}

        <section aria-label="사고 이력" className="pt-2">
          <SectionTitle className="mb-2">사고 이력</SectionTitle>
          {s.listError ? (
            <LoadError onRetry={s.refetchList} />
          ) : (
            <IncidentTable
              incidents={s.incidents}
              selectedId={s.response?.incident.id ?? null}
              openingId={s.opening}
              onOpen={(id) => void s.open(id)}
            />
          )}
        </section>
      </div>
    </PageLayout>
  );
}
