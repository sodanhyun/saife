import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import IncidentForm from "@/pages/Incident/components/IncidentForm";
import IncidentResult from "@/pages/Incident/components/IncidentResult";
import IncidentTable from "@/pages/Incident/components/IncidentTable";
import { useIncident } from "@/pages/Incident/hooks/useIncident";
import IncidentSkeleton from "@/pages/Incident/IncidentSkeleton";

/** UC2 — 등록 버튼 하나로 법정 기한 판정·설비 이력 소환·수시평가 생성·조사표 초안이 동시에 일어난다. */
export default function IncidentPage() {
  const s = useIncident();
  if (s.loading) return <IncidentSkeleton />;
  return (
    <PageLayout>
      <PageHeader title="산업재해 등록" description="등록하면 같은 설비의 이력을 소환하고, 수시평가를 만들고, 법정 제출 기한을 계산합니다." />
      <div className="space-y-6">
        <IncidentForm
          form={s.form}
          setForm={s.setForm}
          equipment={s.equipment}
          plans={s.plans}
          busy={s.busy}
          error={s.error}
          onSubmit={() => void s.submit()}
        />
        {s.response && <IncidentResult r={s.response} />}
        <section>
          <SectionTitle className="mb-2">사고 목록 · 제출 기한</SectionTitle>
          <IncidentTable incidents={s.incidents} />
        </section>
      </div>
    </PageLayout>
  );
}
