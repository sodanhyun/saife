import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import IncidentForm from "@/pages/Incident/components/IncidentForm";
import IncidentProcessing from "@/pages/Incident/components/IncidentProcessing";
import IncidentResult from "@/pages/Incident/components/IncidentResult";
import IncidentTable from "@/pages/Incident/components/IncidentTable";
import { useIncident } from "@/pages/Incident/hooks/useIncident";
import IncidentSkeleton from "@/pages/Incident/IncidentSkeleton";

/** UC2 사고 보고. 보고 한 번으로 사고 전 기록, 수시평가, 조사표 기한, 작업 보류가 이어진다. 결과가 나오면 폼은 물러난다. */
export default function IncidentPage() {
  const s = useIncident();
  if (s.loading) return <IncidentSkeleton />;
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
        {!s.response && (
          <IncidentForm
            form={s.form}
            setForm={s.setForm}
            equipment={s.equipment}
            plans={s.plans}
            busy={s.busy}
            error={s.error}
            onSubmit={() => void s.submit()}
          />
        )}
        {s.busy && <IncidentProcessing />}
        {s.response && <IncidentResult key={s.response.incident.id} r={s.response} />}

        <section aria-labelledby="incident-history-title" className="pt-2">
          {s.loadError && (
            <Callout tone="high" className="mb-3">
              데이터를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 새로고침하세요.
            </Callout>
          )}
          <h2 id="incident-history-title" className="mb-2 text-xs font-bold tracking-wide text-slate-500">
            사고 이력
          </h2>
          <IncidentTable
            incidents={s.incidents}
            selectedId={s.response?.incident.id ?? null}
            onOpen={(id) => void s.open(id)}
          />
        </section>
      </div>
    </PageLayout>
  );
}
