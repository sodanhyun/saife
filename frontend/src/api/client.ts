// axios 인스턴스(REST) + fetch 래퍼(SSE). 인증은 아직 없다 — 붙일 자리만 남긴다.
import axios from "axios";

import { registerAbortController, unregisterAbortController } from "@/utils/abortRegistry";

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

/** 회선이 죽어도 요청이 영원히 매달리지 않게 상한을 둔다. */
export const DEFAULT_TIMEOUT_MS = 60_000;

export const api = axios.create({ timeout: DEFAULT_TIMEOUT_MS });

/**
 * SSE·멀티파트용 fetch 래퍼. non-ok면 본문 message를 담은 ApiError를 던진다 —
 * 스트림 파서가 HTML 에러 페이지를 읽으려 들지 않게 여기서 끊는다.
 */
export async function fetchWithAuth(input: RequestInfo, init?: RequestInit): Promise<Response> {
  let internal: AbortController | null = null;
  let signal = init?.signal;
  if (!signal) {
    internal = new AbortController();
    signal = internal.signal;
    registerAbortController(internal);
  }
  try {
    const res = await fetch(input, { ...init, signal });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      let message = text || `${res.status} ${res.statusText}`;
      try {
        const body = JSON.parse(text) as { message?: string };
        if (body?.message) message = body.message;
      } catch { /* JSON이 아니면 원문 유지 */ }
      throw new ApiError(res.status, message);
    }
    return res;
  } finally {
    if (internal) unregisterAbortController(internal);
  }
}

/** @deprecated 구 페이지 호환용 — Task 13에서 saifeApi.ts와 함께 제거 */
export const get = <T>(path: string) => api.get<T>(path).then((r) => r.data);
/** @deprecated 구 페이지 호환용 — Task 13에서 saifeApi.ts와 함께 제거 */
export const post = <T>(path: string, body?: unknown) => api.post<T>(path, body).then((r) => r.data);
