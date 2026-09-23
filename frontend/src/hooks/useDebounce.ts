// src/hooks/useDebounce.ts

import { useState, useEffect } from "react";

/**
 * 값의 변경을 지연시키는 디바운스 훅.
 * @param value 디바운스할 값
 * @param delayMs 지연 시간 (ms). 기본 300ms
 */
export function useDebounce<T>(value: T, delayMs = 300): T {
  const [debouncedValue, setDebouncedValue] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedValue(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return debouncedValue;
}
