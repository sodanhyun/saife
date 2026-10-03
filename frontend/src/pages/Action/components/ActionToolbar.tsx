// ActionToolbar.tsx: 탭(미이행, 기한 경과, 완료, 전체)과 검색
import { Search } from "lucide-react";

import Input from "@/components/ui/Input";
import SegmentedControl from "@/components/ui/SegmentedControl";
import cn from "@/lib/cn";
import { FILTERS, filterCount, filterLabel, filterName } from "@/pages/Action/utils/actionRow";
import type { ActionCounts, ActionListFilter } from "@/types/action";
import { toneColor } from "@/utils/statusColors";

interface Props {
  filter: ActionListFilter;
  counts: ActionCounts | null;
  keyword: string;
  onFilter: (f: ActionListFilter) => void;
  onKeyword: (v: string) => void;
}

export default function ActionToolbar({ filter, counts, keyword, onFilter, onKeyword }: Props) {
  const options = FILTERS.map((f) => {
    const n = filterCount(f, counts);
    const alert = f === "OVERDUE" && n !== null && n > 0;
    return {
      value: f,
      ariaLabel: filterLabel(f, counts),
      label: (
        <span className="inline-flex items-center gap-1.5">
          {filterName(f)}
          {n !== null && <span className={cn("tabular-nums", alert ? toneColor("high").text : "text-slate-400")}>{n}</span>}
        </span>
      ),
    };
  });

  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
      <SegmentedControl<ActionListFilter>
        ariaLabel="이행 상태"
        variant="pill"
        options={options}
        value={filter}
        onChange={onFilter}
        className="w-full max-w-md"
      />
      <div className="relative w-full sm:w-72">
        <Search aria-hidden="true" size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
        <Input
          aria-label="개선대책 검색"
          placeholder="내용, 설비, 담당"
          value={keyword}
          onChange={(e) => onKeyword(e.target.value)}
          className="bg-white pl-9"
        />
      </div>
    </div>
  );
}
