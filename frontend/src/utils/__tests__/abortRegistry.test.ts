import { describe, expect, it } from "vitest";
import axios from "axios";

import { isAbortError } from "@/utils/abortRegistry";

describe("isAbortError — 취소를 실패로 오인하지 않는 가드", () => {
  it("fetch 취소(AbortError)를 취소로 본다", () => {
    expect(isAbortError(new DOMException("aborted", "AbortError"))).toBe(true);
  });
  it("axios 취소(CanceledError)를 취소로 본다", () => {
    expect(isAbortError(new axios.CanceledError("canceled"))).toBe(true);
  });
  it("진짜 실패는 취소가 아니다", () => {
    expect(isAbortError(new Error("Network error"))).toBe(false);
    expect(isAbortError(new axios.AxiosError("500", "ERR_BAD_RESPONSE"))).toBe(false);
  });
  it("null·undefined에도 터지지 않는다", () => {
    expect(isAbortError(null)).toBe(false);
    expect(isAbortError(undefined)).toBe(false);
  });
});
