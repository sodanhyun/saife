// src/utils/sseGuards.ts

/**
 * SSE payload 타입 검증 가드.
 * useChatStream.ts의 `as` 타입 단언을 런타임 검증으로 대체한다.
 */

/** obj[key]가 string인지 검증 */
export function hasString<K extends string>(
  obj: unknown,
  key: K
): obj is Record<K, string> {
  return (
    typeof obj === "object" &&
    obj !== null &&
    key in obj &&
    typeof (obj as Record<string, unknown>)[key] === "string"
  );
}

/** obj[key]가 number인지 검증 */
export function hasNumber<K extends string>(
  obj: unknown,
  key: K
): obj is Record<K, number> {
  return (
    typeof obj === "object" &&
    obj !== null &&
    key in obj &&
    typeof (obj as Record<string, unknown>)[key] === "number"
  );
}

/** obj가 주어진 shape의 타입을 가지는지 검증 */
export function hasShape<T extends Record<string, unknown>>(
  obj: unknown,
  shape: { [K in keyof T]: "string" | "number" | "boolean" | "object" }
): obj is T {
  if (typeof obj !== "object" || obj === null) return false;
  const record = obj as Record<string, unknown>;
  for (const [key, expectedType] of Object.entries(shape)) {
    if (typeof record[key] !== expectedType) return false;
  }
  return true;
}
