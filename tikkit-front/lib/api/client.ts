import { getSessionCookie, SESSION_COOKIE_NAME } from "@/lib/session";
import type { ApiResponse } from "@/types/api";

const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";

// BE가 내려주는 코드가 아니라 이 래퍼가 직접 만드는 코드. error 화면에서 연결 문제인지 구분할 때 쓴다.
export const NETWORK_ERROR_CODE = "NETWORK_ERROR";
export const INVALID_RESPONSE_CODE = "INVALID_RESPONSE";

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

// 실제로 요청을 보내는 유일한 지점. 세션을 싣는지만 호출부가 정한다.
async function sendRequest(
  path: string,
  init: RequestInit | undefined,
  session: string | undefined
): Promise<Response> {
  try {
    return await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        // Next 도메인 쿠키에 저장해둔 BE 세션을, BE로 나가는 요청에 그대로 실어 보낸다(쿠키 중계).
        ...(session ? { Cookie: `${SESSION_COOKIE_NAME}=${session}` } : {}),
        ...init?.headers,
      },
    });
  } catch {
    // BE가 꺼져 있거나 연결이 끊기면 fetch가 TypeError를 던진다. 호출부가 ApiError 하나만 다루도록 맞춘다.
    throw new ApiError(NETWORK_ERROR_CODE, "서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.");
  }
}

// Set-Cookie 헤더를 직접 봐야 하는 로그인처럼 특수한 경우를 위해, 응답 파싱 전 단계를 따로 노출한다.
export async function apiFetchRaw(path: string, init?: RequestInit): Promise<Response> {
  return sendRequest(path, init, await getSessionCookie());
}

/**
 * 세션을 싣지 않는 공개 조회용 (Task 030_2).
 *
 * 왜 플래그 인자가 아니라 함수를 따로 두는가 — 호출부가 "이건 공개 조회다"를 선언하게
 * 만들고 싶었다. 플래그에 기본값을 두면 새 API를 추가할 때 아무것도 안 적어도 한쪽으로
 * 조용히 떨어지고, 그게 세션이 필요한 경로였으면 인증이 조용히 깨진다(예매 내역이 빈
 * 화면으로 보이는 식으로). 함수가 둘이면 고르지 않을 수 없다.
 *
 * cookies()를 부르지 않는 것 자체가 목적이다. ROADMAP이 Task 030_2에 적은
 * "apiFetchRaw의 cookies() 분리 → revalidate"의 앞 절반이고, fetch 단위 캐시를
 * 켤 수 있는 조건이 된다.
 *
 * 다만 지금은 캐시를 켜지 않았다. 근거는 docs/improvements/009_2에 적었다 —
 *  - 공연 목록: (main)/page.tsx가 force-dynamic으로 "요청마다 최신"을 선언해 뒀다.
 *    캐시하면 예매 오픈이 TTL만큼 늦게 보인다.
 *  - 공연 상세: 029가 이미 BE에서 TTL 60s로 캐시한다. FE에 60s를 더 얹으면 최악 지연이
 *    두 배가 되는데 아끼는 것은 로컬 2~3ms 왕복뿐이다.
 *  - 회차 등급·좌석: remainingQuantity와 status가 실시간이라 영구히 캐시 대상이 아니다
 *    (008/029가 같은 결론에 도달했다).
 */
export async function apiFetchPublicRaw(path: string, init?: RequestInit): Promise<Response> {
  return sendRequest(path, init, undefined);
}

export async function unwrapApiResponse<T>(response: Response): Promise<T> {
  let body: ApiResponse<T>;
  try {
    body = (await response.json()) as ApiResponse<T>;
  } catch {
    // 프록시의 502 HTML 페이지나 빈 body처럼 JSON이 아닌 응답이 오면 json()이 SyntaxError를 던진다.
    throw new ApiError(INVALID_RESPONSE_CODE, "서버 응답을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.");
  }

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

/** 세션 없이 보내는 apiFetch. 공개 조회(공연·회차·좌석)만 쓴다. */
export async function apiFetchPublic<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetchPublicRaw(path, init);
  return unwrapApiResponse<T>(response);
}
