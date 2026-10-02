// WorkPlanRecords.tsx — 점검 기록 목록. 검색(작업명, 설비), 상태 필터, 더 보기.
import Button from "@/components/ui/Button";
import Input from "@/components/ui/Input";
import LoadErrorCallout from "@/components/ui/LoadErrorCallout";
import SectionTitle from "@/components/ui/SectionTitle";
import Select from "@/components/ui/Select";
import WorkPlanTable from "@/pages/WorkPlan/components/WorkPlanTable";
import { WORK_PLAN_STATUS_LABEL, type WorkPlanStatus } from "@/types/domain";
import type { WorkPlanListItem } from "@/types/workPlan";

/** 필터에 보이는 상태. 작성 중(대화 중 초안)과 쓰지 않는 반려는 뺀다 */
const STATUS_OPTIONS: WorkPlanStatus[] = ["SUBMITTED", "APPROVED", "CONDITIONAL", "HOLD", "CLOSED"];

interface Props {
  plans: WorkPlanListItem[];
  total: number;
  hasMore: boolean;
  keyword: string;
  status: WorkPlanStatus | null;
  loadError: boolean;
  onKeyword: (v: string) => void;
  onStatus: (v: WorkPlanStatus | null) => void;
  onMore: () => void;
  onOpen: (id: number) => void;
  onRetry: () => void;
}

export default function WorkPlanRecords({ plans, total, hasMore, keyword, status, loadError, onKeyword, onStatus, onMore, onOpen, onRetry }: Props) {
  const filtered = keyword.trim() !== "" || status !== null;
  return (
    <section aria-label="점검 기록" className="mt-10">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <SectionTitle>점검 기록</SectionTitle>
        <div className="flex items-center gap-2">
          <Input aria-label="작업명, 설비 검색" placeholder="작업명, 설비" value={keyword}
            onChange={(e) => onKeyword(e.target.value)} className="w-56" />
          <Select aria-label="상태" value={status ?? ""} className="w-36"
            onChange={(e) => onStatus(e.target.value === "" ? null : (e.target.value as WorkPlanStatus))}>
            <option value="">전체 상태</option>
            {STATUS_OPTIONS.map((s) => <option key={s} value={s}>{WORK_PLAN_STATUS_LABEL[s]}</option>)}
          </Select>
        </div>
      </div>
      {loadError ? (
        <LoadErrorCallout onRetry={onRetry} />
      ) : (
        <>
          <WorkPlanTable plans={plans} onOpen={onOpen} emptyMessage={filtered ? "조건에 맞는 점검 기록 없음" : "점검 기록 없음"} />
          {hasMore && (
            <div className="mt-3 flex justify-center">
              <Button variant="secondary" size="sm" onClick={onMore}>더 보기 ({plans.length}/{total})</Button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
