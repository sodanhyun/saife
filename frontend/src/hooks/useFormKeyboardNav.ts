// src/hooks/useFormKeyboardNav.ts
import { useEffect, useRef, type RefObject } from "react";

/**
 * 전역 키보드 인터랙션 표준 훅 (폼·입력 화면·모달 공통).
 *
 * enabled(활성)일 때 컨테이너 내에서:
 * 1. 자동 포커스 — 활성화 시 가장 좌측 상단(DOM 순서 첫) 포커스가능 input에 focus.
 * 2. 화살표 위/아래 — 인접 input으로 포커스 이동(순환 없음, 끝에서 멈춤).
 *    좌우 화살표는 텍스트 커서 이동과 충돌하므로 다루지 않는다.
 * 3. Enter — textarea가 아니면 preventDefault 후 onSave 호출(줄바꿈은 textarea에서 유지).
 *
 * 화살표 가로채기는 대상이 단일행 <input>일 때만 수행한다. <select>(위/아래=옵션 순환)와
 * <textarea>(위/아래=줄 이동)는 네이티브 키 동작을 보존해야 하므로 가로채지 않는다. 버튼 등 그 외 요소도 간섭 없음.
 */
interface UseFormKeyboardNavOptions {
  /** 포커스/키다운 범위가 되는 컨테이너 요소 ref. */
  containerRef: RefObject<HTMLElement | null>;
  /** Enter 시 실행할 저장 액션. 저장 비활성 조건은 호출부에서 가드한다(내부에서 무시하려면 no-op 전달). */
  onSave?: () => void;
  /** 훅 활성 여부(기본 true). false면 자동 포커스·리스너 모두 비활성. */
  enabled?: boolean;
  /**
   * 자동 포커스를 다시 트리거하는 임의 키. 값이 바뀌면(enabled 유지 상태에서도) 첫 input에 재포커스한다
   * — 다단계 위저드의 스텝 전환 등에서 사용한다. 미지정 시 enabled 전환 시에만 포커스한다.
   */
  autoFocusKey?: unknown;
  /** false면 자동 포커스를 하지 않는다(기본 true). */
  autoFocus?: boolean;
  /** false면 Enter 저장을 다루지 않는다(기본 true). 모달처럼 자체 Enter 핸들러가 있을 때 사용. */
  handleEnter?: boolean;
  /**
   * 자동 포커스를 걸 요소의 CSS 선택자 — 지정하면 DOM 첫 입력 대신 이것을 잡는다.
   *
   * 기본(첫 입력)이 늘 옳지는 않다: 실행 모달처럼 **체크박스가 먼저 나오고 사람이 채워야 할 칸은
   * 아래에 있는** 화면에서는 첫 입력에 걸린 포커스가 오히려 손을 한 번 더 쓰게 만든다.
   * 선택자가 아무것도 못 찾으면 기본 규칙으로 되돌아간다.
   */
  autoFocusSelector?: string;
}

/** 컨테이너 스코프 내 포커스가능 폼 요소를 DOM 순서로 반환(disabled/hidden 제외). */
export function getFocusableFormElements(container: HTMLElement): HTMLElement[] {
  const nodes = Array.from(
    container.querySelectorAll<HTMLElement>(
      'input, select, textarea, [tabindex]:not([tabindex="-1"])'
    )
  );
  return nodes.filter((el) => {
    // 표 행은 클릭 가능하게 tabindex+role=button을 갖지만 폼 입력이 아니다. 이걸 포함하면 화면 진입 시
    // 자동 포커스가 결과표 첫 행에 걸려 굵은 포커스 테두리가 먼저 눈에 들어온다(입력 칸이 아닌데도).
    if (el.getAttribute("role") === "button") return false;
    if (el.hasAttribute("disabled")) return false;
    if ((el as HTMLInputElement).type === "hidden") return false;
    if (el.hidden) return false;
    if (el.getAttribute("aria-hidden") === "true") return false;
    return true;
  });
}

/** 폼/모달의 전역 키보드 인터랙션 표준을 적용한다. 반환값 없음. */
export function useFormKeyboardNav({
  containerRef,
  onSave,
  enabled = true,
  autoFocusKey,
  autoFocus = true,
  handleEnter = true,
  autoFocusSelector,
}: UseFormKeyboardNavOptions): void {
  // onSave는 렌더마다 새 함수일 수 있으므로 ref로 최신값을 참조(리스너 재등록 방지).
  const onSaveRef = useRef(onSave);
  useEffect(() => {
    onSaveRef.current = onSave;
  });

  // 1) 자동 포커스 — enabled 전환 또는 autoFocusKey 변경 시 첫 포커스가능 input에 focus.
  useEffect(() => {
    if (!enabled || !autoFocus) return;
    const container = containerRef.current;
    if (!container) return;
    const preferred = autoFocusSelector
      ? container.querySelector<HTMLElement>(autoFocusSelector)
      : null;
    if (preferred) {
      preferred.focus({ preventScroll: true });
      return;
    }
    const focusables = getFocusableFormElements(container);
    focusables[0]?.focus({ preventScroll: true });
  }, [enabled, autoFocus, autoFocusKey, autoFocusSelector, containerRef]);

  // 2) 키다운(화살표 이동 + Enter 저장) — 컨테이너 스코프.
  useEffect(() => {
    if (!enabled) return;
    const container = containerRef.current;
    if (!container) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (!target) return;
      const tag = target.tagName;

      if (handleEnter && e.key === "Enter") {
        // textarea는 줄바꿈 유지 — 저장하지 않는다.
        if (tag === "TEXTAREA") return;
        // 포커스된 버튼·링크에서 Enter는 그 요소를 활성화해야 한다(표준 동작).
        // 가로채면 섹션 접기 토글 같은 폼 내 버튼이 눌리지 않고 저장이 발동한다.
        if (tag === "BUTTON" || tag === "A") return;
        e.preventDefault();
        onSaveRef.current?.();
        return;
      }

      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        // 단일행 <input>에서만 포커스 이동을 가로챈다 — <select>(옵션 순환)·<textarea>(줄 이동)와
        // 버튼 등은 네이티브 동작을 보존한다.
        if (tag !== "INPUT") return;
        const focusables = getFocusableFormElements(container);
        const idx = focusables.indexOf(target);
        if (idx === -1) return;
        const nextIdx = e.key === "ArrowDown" ? idx + 1 : idx - 1;
        // 순환 없음 — 끝에서 멈춘다.
        if (nextIdx < 0 || nextIdx >= focusables.length) return;
        e.preventDefault();
        focusables[nextIdx].focus();
      }
    };

    container.addEventListener("keydown", handleKeyDown);
    return () => container.removeEventListener("keydown", handleKeyDown);
  }, [enabled, handleEnter, containerRef]);
}
