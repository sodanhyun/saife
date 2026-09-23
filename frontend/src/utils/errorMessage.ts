// src/utils/errorMessage.ts
// 백엔드 공통 에러 응답(body.message)에서 사용자 표시용 메시지를 안전하게 추출한다.
// 서버가 구체 사유(예: 중복 코드 409 "이미 존재하는 코드입니다")를 내려주면 그대로 노출하고,
// 없으면 undefined를 반환해 호출부의 제네릭 메시지로 폴백한다.
import { isAxiosError } from "axios";

export function getServerMessage(err: unknown): string | undefined {
  if (!isAxiosError(err)) return undefined;
  const data = err.response?.data as { message?: unknown } | undefined;
  return typeof data?.message === "string" && data.message.trim() ? data.message : undefined;
}
