// fetchWithAuth의 에러 메시지 파생 — HTML·과대 본문을 사용자에게 그대로 보여주지 않는다.
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, fetchWithAuth } from "@/api/client";

const originalFetch = global.fetch;

describe("fetchWithAuth", () => {
  afterEach(() => {
    global.fetch = originalFetch;
  });

  it("HTML 본문이면 상태 코드·문구로 대체한다", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response("<html>502</html>", { status: 502, statusText: "Bad Gateway" }));

    await expect(fetchWithAuth("/x")).rejects.toBeInstanceOf(ApiError);
    await expect(fetchWithAuth("/x")).rejects.toMatchObject({
      status: 502,
      message: "502 Bad Gateway",
    });
  });

  it("JSON 본문의 message를 그대로 쓴다", async () => {
    global.fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ message: "설비를 찾을 수 없습니다" }), { status: 404, statusText: "Not Found" }),
    );

    await expect(fetchWithAuth("/x")).rejects.toMatchObject({
      status: 404,
      message: "설비를 찾을 수 없습니다",
    });
  });

  it("일반 텍스트 본문이면 그 문구를 그대로 쓴다", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response("요청을 처리할 수 없습니다", { status: 400, statusText: "Bad Request" }));

    await expect(fetchWithAuth("/x")).rejects.toMatchObject({
      status: 400,
      message: "요청을 처리할 수 없습니다",
    });
  });
});
