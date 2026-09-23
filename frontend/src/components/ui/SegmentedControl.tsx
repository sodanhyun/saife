// src/components/ui/SegmentedControl.tsx
import type { ReactNode } from "react";

import cn from "@/lib/cn";

export interface SegmentedOption<T> {
  value: T;
  label: ReactNode;
  disabled?: boolean;
  /** 선택 시 색 — 미지정이면 기본(솔리드=slate-900 / 필=흰 노브). 판정색 같은 의미색만 여기로 준다. */
  selectedClassName?: string;
  /** pill 변형에서 선택된 라벨 글자색 — 노브 색과 짝을 이룰 때만 지정(미지정 시 slate-900). */
  selectedTextClassName?: string;
  /** 스크린리더용 버튼 이름 — 라벨만으로 대상을 알 수 없을 때(같은 라벨이 여러 행에 반복될 때) 지정. */
  ariaLabel?: string;
}

interface SegmentedControlProps<T> {
  options: SegmentedOption<T>[];
  /** 현재 선택값. null이면 아무것도 선택되지 않은 상태(예: 정성 미판독)를 그대로 그린다. */
  value: T | null;
  onChange: (value: T) => void;
  /** 그룹 이름(스크린리더) — 필수. 무엇을 고르는 묶음인지 이름이 없으면 읽히지 않는다. */
  ariaLabel: string;
  /**
   * solid — 세그먼트가 맞붙은 테두리형. 선택된 칸만 색이 찬다(판정 선택·방향 지시).
   * pill  — 회색 트랙 위를 흰 노브가 옮겨 다니는 스위치형(2~3지 판독·부수 옵션).
   */
  variant?: "solid" | "pill";
  /** 타깃 크기 — 장갑 낀 손(모바일)은 lg, 표 안 조밀한 자리는 sm. */
  size?: "sm" | "md" | "lg";
  /** 이미 선택된 값을 다시 누르면 해제 — 해제 시 onDeselect가 불린다. */
  onDeselect?: () => void;
  className?: string;
}

const SIZE: Record<"sm" | "md" | "lg", string> = {
  sm: "min-h-[26px] px-2 text-xs",
  md: "min-h-[42px] px-3 text-sm",
  lg: "min-h-[56px] px-3 text-base",
};

/**
 * 단일 선택 세그먼트 컨트롤.
 *
 * 판정 2분할·정성 OK/NOK 판독·조정 방향 지시가 각자 인라인 `aria-pressed` 버튼 묶음으로
 * 네 벌 있었고, 높이·선택색·테두리 처리가 조금씩 어긋나 있었다. 한 구현으로 모아 그 어긋남까지 정렬한다.
 *
 * 선택 없음(value=null)을 1급 상태로 다룬다 — pill 변형은 그때 노브를 그리지 않는다. 스위치는 본래
 * 2상태지만 "아직 안 눌렀다"를 어느 한쪽이 켜진 모습으로 보여주면 판독 안 한 항목이 판독된 것처럼 읽힌다.
 */
export default function SegmentedControl<T extends string | number | boolean>({
  options,
  value,
  onChange,
  ariaLabel,
  variant = "solid",
  size = "md",
  onDeselect,
  className,
}: SegmentedControlProps<T>) {
  const selectedIndex = options.findIndex((o) => o.value === value);
  const handle = (option: SegmentedOption<T>) => {
    if (option.disabled) return;
    if (option.value === value && onDeselect) {
      onDeselect();
      return;
    }
    onChange(option.value);
  };

  if (variant === "pill") {
    return (
      <div
        role="group"
        aria-label={ariaLabel}
        className={cn("relative flex items-center rounded-full bg-slate-100 p-0.5", className)}
      >
        {/* 노브 — 선택 칸 위로 미끄러진다. 폭·이동거리는 칸 수에 따라 달라져 인라인 스타일로 계산한다. */}
        {selectedIndex >= 0 && (
          <span
            aria-hidden="true"
            className={cn(
              "absolute inset-y-0.5 left-0.5 rounded-full transition-transform duration-150 motion-reduce:transition-none",
              options[selectedIndex].selectedClassName ?? "bg-white shadow-[0_1px_2px_rgba(15,23,42,0.12)]",
            )}
            style={{
              width: `calc((100% - 0.25rem) / ${options.length})`,
              transform: `translateX(${selectedIndex * 100}%)`,
            }}
          />
        )}
        {options.map((option) => {
          const selected = option.value === value;
          return (
            <button
              key={String(option.value)}
              type="button"
              aria-pressed={selected}
              aria-label={option.ariaLabel}
              disabled={option.disabled}
              onClick={() => handle(option)}
              className={cn(
                "relative z-10 flex-1 text-center font-semibold whitespace-nowrap transition-colors",
                "disabled:opacity-40 disabled:cursor-not-allowed",
                SIZE[size],
                selected
                  ? (option.selectedTextClassName ?? "text-slate-900")
                  : "text-slate-400 hover:text-slate-600",
              )}
            >
              {option.label}
            </button>
          );
        })}
      </div>
    );
  }

  return (
    <div role="group" aria-label={ariaLabel} className={cn("flex", className)}>
      {options.map((option, i) => {
        const selected = option.value === value;
        return (
          <button
            key={String(option.value)}
            type="button"
            aria-pressed={selected}
            aria-label={option.ariaLabel}
            disabled={option.disabled}
            onClick={() => handle(option)}
            className={cn(
              "flex-1 font-bold border whitespace-nowrap transition-colors",
              "disabled:opacity-40 disabled:cursor-not-allowed",
              SIZE[size],
              // 맞붙은 세그먼트 — 가운데 테두리가 두 겹으로 굵어지지 않게 1px 포갠다.
              // border-l-0으로 지우면 선택된 칸에 색 테두리를 줬을 때 왼쪽 변만 없는 상태가 된다.
              i > 0 && "-ml-px",
              // 선택된 칸이 이웃의 테두리에 덮이지 않도록 위로 올린다.
              selected
                ? cn("relative z-10", option.selectedClassName ?? "bg-slate-900 border-slate-900 text-white")
                : "bg-white border-slate-200 text-slate-500 hover:bg-slate-50",
            )}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
