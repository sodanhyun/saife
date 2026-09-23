// src/components/ui/TabBar.tsx
import type { ReactNode } from "react";

import cn from "@/lib/cn";

export interface Tab<T extends string = string> {
  key: T;
  label: string;
}

interface TabBarProps<T extends string = string> {
  tabs: Tab<T>[];
  activeTab: T;
  onChange: (tab: T) => void;
  className?: string;
  /** 탭 변형: default(밑줄), secondary(세그먼트 컨트롤) */
  variant?: "default" | "secondary";
  /**
   * 탭 줄 우측 끝에 붙일 노드(예: 콘솔 현재 버전 + 발행 버튼).
   *
   * <p>탭과 같은 줄을 쓰는 이유 — 별도 섹션으로 띄우면 그만큼 표가 밀리는데, 실을 내용은
   * 텍스트 한 줄과 버튼 하나뿐이라 한 줄을 통째로 쓸 값어치가 없다. secondary 변형에는 쓰지 않는다.
   */
  trailing?: ReactNode;
}

/**
 * 페이지 내부 탭 바 공통 컴포넌트.
 * default: border-b-2 밑줄 스타일.
 * secondary: pill/세그먼트 컨트롤 스타일 (서브탭 등 하위 네비게이션용).
 */
export default function TabBar<T extends string = string>({
  tabs,
  activeTab,
  onChange,
  className,
  variant = "default",
  trailing,
}: TabBarProps<T>) {
  const isSecondary = variant === "secondary";

  return (
    <div
      className={cn(
        isSecondary
          // w-fit — 세로 flex 컨테이너(ListPageSection 등)의 자식일 때 cross-size가 auto면
          // align-items:stretch로 가로 전폭이 된다. width를 fit-content로 고정해 탭 버튼 폭만 배경이 감싸게 한다
          // (inline-flex만으로는 flex item stretch를 막지 못한다).
          ? "bg-slate-100 rounded-lg p-0.5 inline-flex w-fit gap-0.5"
          // flex-wrap — trailing(발행 컨트롤)이 폭을 가져가면 좁은 화면에서 탭 라벨이 두 줄로 접혔다.
          // 접히는 쪽을 탭이 아니라 trailing으로 만든다(탭은 whitespace-nowrap로 한 줄 고정).
          : "flex flex-wrap items-center gap-1",
        !isSecondary && "border-b border-slate-200 mb-3",
        className
      )}
    >
      {tabs.map((tab) => (
        <button
          key={tab.key}
          onClick={() => onChange(tab.key)}
          className={cn(
            "whitespace-nowrap transition-colors",
            isSecondary
              ? cn(
                  "px-3 py-1.5 text-xs font-medium rounded-md",
                  activeTab === tab.key
                    ? "bg-white text-slate-900 shadow-sm"
                    : "text-slate-500 hover:text-slate-700"
                )
              : cn(
                  "border-b-2 -mb-px px-4 py-2 text-sm font-medium",
                  activeTab === tab.key
                    ? "border-slate-900 text-slate-900"
                    : "border-transparent text-slate-500 hover:text-slate-700"
                )
          )}
        >
          {tab.label}
        </button>
      ))}
      {trailing && !isSecondary && <div className="ml-auto flex shrink-0 items-center gap-2 pb-1">{trailing}</div>}
    </div>
  );
}
