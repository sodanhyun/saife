/**
 * 얇은 fetch 래퍼.
 *
 * axios를 쓰지 않는 이유: SSE를 fetch + ReadableStream으로 직접 읽어야 해서
 * 어차피 fetch가 필요하고, 두 방식을 섞으면 에러 처리가 두 벌이 된다.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new ApiError(res.status, body || `${res.status} ${res.statusText}`);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export async function get<T>(path: string): Promise<T> {
  return handle<T>(await fetch(path));
}

export async function post<T>(path: string, body?: unknown): Promise<T> {
  return handle<T>(
    await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
}
