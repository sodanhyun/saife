// 글로벌 AbortController 레지스트리 — in-flight 요청을 한 번에 중단할 때 쓴다.
import axios from "axios";

const registry = new Set<AbortController>();

export function registerAbortController(c: AbortController) { registry.add(c); }
export function unregisterAbortController(c: AbortController) { registry.delete(c); }
export function abortAllRequests() { registry.forEach((c) => c.abort()); registry.clear(); }

/**
 * catch 블록 공통 가드 — 요청이 취소된 것이면 true. true면 에러 UI를 띄우지 않는다.
 * fetch는 `AbortError`, axios는 `CanceledError`를 던진다. 한쪽만 보면 다른 쪽 취소가 전부 토스트가 된다.
 */
export function isAbortError(err: unknown): boolean {
  if ((err as { name?: string })?.name === "AbortError") return true;
  return axios.isCancel(err);
}
