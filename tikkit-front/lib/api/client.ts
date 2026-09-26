import { getSessionCookie, SESSION_COOKIE_NAME } from "@/lib/session";
import type { ApiResponse } from "@/types/api";

const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";

// ApiResponse 포맷을 언랩하는 fetch 래퍼.
// API_BASE_URL은 NEXT_PUBLIC_ 접두사가 없어 브라우저 번들에는 포함되지 않는다 — 이 함수는
// Server Component/Server Action 전용이며, 클라이언트 컴포넌트에서 직접 호출하면 안 된다.
export class ApiError extends Error {
  code: string;
  errors: string[];

  constructor(code: string, message: string, errors: string[] = []) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.errors = errors;
  }
}

// Set-Cookie 헤더를 직접 봐야 하는 로그인처럼 특수한 경우를 위해, 응답 파싱 전 단계를 따로 노출한다.
export async function apiFetchRaw(path: string, init?: RequestInit): Promise<Response> {
  const session = await getSessionCookie();

  return fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      // Next 도메인 쿠키에 저장해둔 BE 세션을, BE로 나가는 요청에 그대로 실어 보낸다(쿠키 중계).
      ...(session ? { Cookie: `${SESSION_COOKIE_NAME}=${session}` } : {}),
      ...init?.headers,
    },
  });
}

export async function unwrapApiResponse<T>(response: Response): Promise<T> {
  const body = (await response.json()) as ApiResponse<T>;

  if (!body.success) {
    throw new ApiError(
      body.code ?? "UNKNOWN_ERROR",
      body.message ?? "요청 처리 중 오류가 발생했습니다.",
      body.errors ?? []
    );
  }

  return body.data as T;
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetchRaw(path, init);
  return unwrapApiResponse<T>(response);
}
