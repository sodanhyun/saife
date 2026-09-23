// src/components/ui/DataTable.tsx
import React from "react";

import { ArrowUpDown, ArrowUp, ArrowDown } from "lucide-react";

import cn from "@/lib/cn";
import EmptyState from "./EmptyState";

export interface Column<T> {
  key: string;
  /** 컬럼 헤더. 문자열이 기본이지만, 필수 표시(*) 같은 장식 요소를 붙이기 위해 노드도 허용한다. */
  header: React.ReactNode;
  /** Tailwind 너비 클래스 (예: "w-[20%]") */
  width?: string;
  align?: "left" | "center" | "right";
  /** 정렬 가능 컬럼 여부 */
  sortable?: boolean;
  /** 정렬 헤더 스크린리더 힌트 — 지정 시 th aria-label(예 "검사일 기준 정렬"). 미지정 시 기존처럼 없음. */
  ariaLabel?: string;
  /** true이면 td에 truncate 대신 overflow-visible 적용 (Select 등 인터랙티브 요소용) */
  noTruncate?: boolean;
  /** adaptiveWidth 모드에서 이 컬럼만 줄바꿈을 허용한다(기본은 전 컬럼 nowrap).
   *  본문이 긴 컬럼 하나에 `width: "w-full"`과 함께 주면, 나머지 컬럼은 내용 폭에 딱 맞게 줄고
   *  남는 가로폭 전부를 이 컬럼이 흡수한다 — 내용이 많은 셀의 줄바꿈이 최소화된다.
   *  adaptiveWidth가 아닐 때는 무시된다(기존 truncate 동작 유지). */
  wrap?: boolean;
  /** 셀 클릭 핸들러 — 지정 시 해당 컬럼의 td 클릭이 onRowClick 대신 이 핸들러를 실행 */
  onCellClick?: (row: T) => void;
  /** td에 덧붙이는 클래스 — 기본값을 덮어쓸 수 있다(tailwind-merge). 예: 선택 셀의 `cursor-default`
   *  — 행 클릭(상세 이동)과 셀 클릭(체크)은 하는 일이 다르므로 커서로 구분한다. */
  cellClassName?: string;
  render: (row: T, index: number) => React.ReactNode;
}

/** 그룹 헤더 1칸 — 컬럼 헤더 위에 얹히는 상위 묶음. span은 덮는 컬럼 수. */
export interface HeaderGroup {
  label: string;
  span: number;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  /**
   * 표 위에서 도는 작업의 진행률(0~100). `-1`이면 총량을 모르는 상태(무한 진행), `null`/생략이면
   * 막대를 그리지 않는다.
   *
   * 진행은 **그 표에서 일어나는 일**이므로 표가 그린다 — 밖에 얹으면 둥근 모서리와 어긋나고,
   * 화면에 상자가 하나 더 생긴다.
   */
  progressPercent?: number | null;
  /** 컬럼 헤더 위 그룹 행(선택). 컬럼 순서대로 나열하며 span 합계 = columns.length 여야 한다. */
  headerGroups?: HeaderGroup[];
  /** 컬럼 사이 세로 구분선(선택). 값 성격이 다른 열이 여럿 붙어 어디까지가 한 열인지 흐려지는 표에만 옵트인 —
   *  기본은 가로선만 있는 기존 톤을 유지한다(MES 밀도: 선을 늘리는 건 필요한 표에서만). */
  columnDividers?: boolean;
  data: T[];
  rowKey: (row: T) => string | number;
  onRowClick?: (row: T) => void;
  emptyMessage?: string;
  className?: string;
  /** 행 DOM id 접두사 — 지정 시 각 행에 `{prefix}-{rowKey}` id 부여 (스크롤 타겟 등) */
  rowIdPrefix?: string;
  /** 정렬 상태 — 현재 정렬 키 */
  sortKey?: string | null;
  /** 정렬 방향 */
  sortOrder?: "asc" | "desc";
  /** 정렬 헤더 클릭 콜백 */
  onSort?: (key: string) => void;
  /** 펼침 행 — 현재 펼쳐진 행의 키 */
  expandedRowKey?: string | number | null;
  /** 펼침 행 콘텐츠 렌더 함수 */
  renderExpanded?: (row: T) => React.ReactNode;
  /** 행 마우스 진입 콜백 (프리페치 등) */
  onRowMouseEnter?: (row: T) => void;
  /** 행별 추가 클래스 (조건부 배경 등) */
  rowClassName?: (row: T) => string | undefined;
  /** tbody 첫 행 앞에 렌더링할 콘텐츠 (인라인 추가 행 등). colSpan은 columns.length 자동 전달. */
  renderBeforeRows?: (colSpan: number) => React.ReactNode;
  /** tbody 마지막 행 뒤에 렌더링할 콘텐츠 (합계 행 등). colSpan은 columns.length 자동 전달. */
  renderAfterRows?: (colSpan: number) => React.ReactNode;
  /** 테이블 최소 너비(예 "min-w-[900px]"). 지정 시 래퍼가 가로 스크롤로 전환된다 —
   *  컬럼이 많아 좁은 폭에서 뭉개지는 표에만 쓴다(미지정 시 기존 동작 유지). */
  minWidthClass?: string;
  /** 컬럼 너비를 내용에 맞춰 fit — table-fixed+truncate 대신 table-auto+whitespace-nowrap.
   *  읽기 전용 표에만 옵트인(콘솔 마스터 표는 인라인 입력 폼의 td 고유 너비가 컬럼을 벌리므로 대상 제외).
   *  미지정 시 기존 table-fixed+truncate 동작을 100% 유지한다. minWidthClass와 함께 써도 충돌 없음
   *  (가로 스크롤 여부만 minWidthClass가 결정, 컬럼 너비 계산 방식은 이 prop이 결정). */
  adaptiveWidth?: boolean;
  /**
   * 높이가 정해진 껍데기 안에서 **표만 스크롤**하게 만든다(콘솔 탭 본문 등 — consoleLayout 계약).
   *
   * 래퍼가 스크롤 상자가 되고 컬럼 헤더는 sticky로 고정된다. 스크롤 상자를 바깥 div에 따로 두면 안 된다 —
   * 기본값인 `overflow-hidden`도 스크롤포트를 만들기 때문에 그 안의 sticky 헤더가 죽는다. 스크롤 상자와
   * sticky 헤더는 반드시 같은 래퍼에 있어야 한다.
   *
   * `flex-1`을 주지 않는 것이 의도다 — 주면 행이 적어도 상자가 남은 높이만큼 늘어나 표 아래에
   * 테두리만 있는 빈 여백이 생긴다. 기본 shrink에 맡기면 내용이 짧을 땐 내용에 딱 맞고, 껍데기를
   * 넘길 때만 줄어들며 안에서 스크롤된다.
   *
   * 데스크톱(lg↑)에서만 높이가 제한되므로 모바일에서는 스크롤이 생기지 않는다(내용대로 흐른다).
   */
  fill?: boolean;
}

const alignClass = {
  left: "text-left",
  center: "text-center",
  right: "text-right",
} as const;

/**
 * 표준 데이터 테이블.
 * 카드 래퍼 + 고정 레이아웃 테이블 + 빈 상태 자동 표시.
 * 선택적 기능: 정렬 헤더, 펼침 행, 행 호버 콜백.
 */
export default function DataTable<T>({
  columns,
  progressPercent = null,
  headerGroups,
  columnDividers,
  data,
  rowKey,
  onRowClick,
  emptyMessage,
  className,
  sortKey,
  sortOrder,
  onSort,
  expandedRowKey,
  renderExpanded,
  onRowMouseEnter,
  rowClassName,
  rowIdPrefix,
  renderBeforeRows,
  renderAfterRows,
  minWidthClass,
  adaptiveWidth,
  fill,
}: DataTableProps<T>) {
  if (data.length === 0 && !renderBeforeRows) {
    return <EmptyState message={emptyMessage ?? "데이터가 없습니다"} className={className} />;
  }

  /** 정렬 아이콘 렌더링 */
  const renderSortIcon = (col: Column<T>) => {
    if (!col.sortable) return null;
    const isActive = sortKey === col.key;
    const IconComponent = !isActive
      ? ArrowUpDown
      : sortOrder === "asc"
        ? ArrowUp
        : ArrowDown;
    return (
      <IconComponent
        size={13}
        className={cn(
          "inline-block ml-1",
          isActive ? "text-slate-800" : "text-slate-400"
        )}
      />
    );
  };

  return (
    <div
      className={cn(
        "relative border border-slate-200 rounded-lg bg-white shadow-card",
        // fill 모드는 이 래퍼가 스크롤 상자다(세로는 항상, 가로는 최소 너비를 준 표만).
        // 그 밖에는 종전대로 — 최소 너비를 준 표만 가로 스크롤하고 나머지는 잘라낸다.
        fill && cn("overflow-y-auto lg:min-h-0", minWidthClass ? "overflow-x-auto" : "overflow-x-hidden"),
        !fill && (minWidthClass ? "overflow-x-auto" : "overflow-hidden"),
        className
      )}
    >
      {/* 진행 막대 — **표 상자 안**의 맨 윗줄. 상자가 overflow를 자르므로 둥근 모서리가 자동으로 맞는다. */}
      {progressPercent != null && (
        <div className="absolute inset-x-0 top-0 z-20 h-1 overflow-hidden bg-slate-200/70">
          <div
            className={cn("h-full bg-slate-900", progressPercent < 0 ? "w-1/3 animate-pulse" : "transition-all")}
            style={progressPercent >= 0 ? { width: `${Math.min(100, progressPercent)}%` } : undefined}
            role="progressbar"
            aria-valuenow={progressPercent >= 0 ? progressPercent : undefined}
          />
        </div>
      )}

      <table className={cn("w-full text-sm", adaptiveWidth ? "table-auto" : "table-fixed", minWidthClass)}>
        {/* fill 모드에서는 표 머리를 고정한다 — 표 안에서 스크롤할 때 컬럼 의미를 잃지 않게.
            헤더 행들이 이미 bg-slate-50을 갖고 있어 뒤 행이 비쳐 보이지 않는다. */}
        <thead className={cn(fill && "sticky top-0 z-10")}>
          {/* 그룹 헤더 행(선택) — 컬럼 순서대로 span을 채운다. span 합계가 columns.length와 다르면 표가 어긋나므로
              호출부가 전 컬럼을 빠짐없이 덮어야 한다(묶이지 않는 컬럼은 label="" + span=1). */}
          {headerGroups && (
            <tr className="text-xs text-slate-500 border-b border-slate-200 bg-slate-50">
              {headerGroups.map((group, i) => (
                <th
                  key={`${group.label}-${i}`}
                  colSpan={group.span}
                  className={cn(
                    "px-3 py-2 font-semibold uppercase tracking-wide",
                    group.span > 1 ? "text-center" : "text-left",
                    i > 0 && "border-l border-slate-200"
                  )}
                >
                  {group.label}
                </th>
              ))}
            </tr>
          )}
          <tr className="text-left text-xs text-slate-600 border-b border-slate-200 bg-slate-50">
            {columns.map((col, i) => (
              <th
                key={col.key}
                aria-label={col.ariaLabel}
                className={cn(
                  "px-3 py-2 font-semibold",
                  columnDividers && i > 0 && "border-l border-slate-200",
                  // 내용 맞춤 폭에서는 머리글도 접지 않는다 — 셀보다 머리글이 길 때 컬럼이
                  // 머리글만 두 줄로 접히며 좁아지는 걸 막는다(wrap 컬럼은 예외).
                  adaptiveWidth && !col.wrap && "whitespace-nowrap",
                  col.width,
                  col.align && alignClass[col.align],
                  col.sortable && onSort && "cursor-pointer select-none hover:bg-slate-100 transition-colors"
                )}
                onClick={col.sortable && onSort ? () => onSort(col.key) : undefined}
              >
                {col.header}
                {renderSortIcon(col)}
              </th>
            ))}
          </tr>
        </thead>
        {/* 행 구분선은 slate-200 — 흐린 회색은 흰 배경을 전제한 색이라, 행에 연톤(판정색·지시
            하이라이트)이 깔리면 배경보다 밝아져 선이 통째로 사라진다. 어떤 톤 위에서도 남는
            농도를 기본값으로 둔다(흰 배경에서도 여전히 옅다). */}
        <tbody className="divide-y divide-slate-200">
          {renderBeforeRows?.(columns.length)}
          {data.length === 0 && (
            <tr>
              <td colSpan={columns.length} className="px-3 py-8 text-center text-sm text-slate-400">
                {emptyMessage ?? "데이터가 없습니다"}
              </td>
            </tr>
          )}
          {data.map((row, index) => {
            const key = rowKey(row);
            const isExpanded = expandedRowKey != null && key === expandedRowKey;
            return (
              <React.Fragment key={key}>
                <tr
                  id={rowIdPrefix ? `${rowIdPrefix}-${key}` : undefined}
                  tabIndex={onRowClick ? 0 : undefined}
                  role={onRowClick ? "button" : undefined}
                  className={cn(
                    "hover:bg-slate-50 transition-colors",
                    onRowClick &&
                      "cursor-pointer outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-slate-500",
                    isExpanded && "bg-slate-50",
                    rowClassName?.(row)
                  )}
                  // 행 어디를 클릭하든 onRowClick 실행. 단, 버튼·입력 등 인터랙티브 요소에서 시작된 클릭은
                  // 제외해 행 위 버튼(편집·이력·토글)·인풋과 이벤트가 겹치지 않게 한다.
                  onClick={
                    onRowClick
                      ? (e) => {
                          if ((e.target as HTMLElement).closest("button, input, select, textarea, a, label")) return;
                          onRowClick(row);
                        }
                      : undefined
                  }
                  // 키보드 접근성: Enter/Space로 행 활성화(role="button" 관례). 인터랙티브 자식(버튼 등)에서
                  // 발생한 키 입력은 그 요소가 스스로 처리하므로 onRowClick 중복 호출을 제외한다(위 onClick과 동일 가드).
                  onKeyDown={
                    onRowClick
                      ? (e) => {
                          if (e.key !== "Enter" && e.key !== " ") return;
                          if ((e.target as HTMLElement).closest("button, input, select, textarea, a, label")) return;
                          e.preventDefault();
                          onRowClick(row);
                        }
                      : undefined
                  }
                  onMouseEnter={onRowMouseEnter ? () => onRowMouseEnter(row) : undefined}
                >
                  {columns.map((col, i) => (
                    <td
                      key={col.key}
                      className={cn(
                        "px-3 py-2",
                        columnDividers && i > 0 && "border-l border-slate-100",
                        adaptiveWidth
                          ? col.wrap ? "whitespace-normal break-words" : "whitespace-nowrap"
                          : col.noTruncate ? "overflow-visible" : "truncate",
                        col.align && alignClass[col.align],
                        // group/cell — 셀에 걸린 hover를 자식(체크박스 등)이 받아 "여기를 누르면 된다"를
                        // 표시할 수 있게 한다. 셀 전체가 표적이라는 사실은 커서만으로는 잘 읽히지 않는다.
                        col.onCellClick && "cursor-pointer group/cell",
                        col.cellClassName
                      )}
                      onClick={col.onCellClick ? (e) => { e.stopPropagation(); col.onCellClick!(row); } : undefined}
                    >
                      {col.render(row, index)}
                    </td>
                  ))}
                </tr>
                {isExpanded && renderExpanded && (
                  <tr key={`${key}-expanded`} className="border-t border-slate-100">
                    <td colSpan={columns.length} className="p-0">
                      {renderExpanded(row)}
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
          {renderAfterRows?.(columns.length)}
        </tbody>
      </table>
    </div>
  );
}
