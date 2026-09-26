import { ApiError, apiFetch, apiFetchRaw, unwrapApiResponse } from "@/lib/api/client";
import { clearSessionCookie, setSessionCookie } from "@/lib/session";
import type { LoginRequest, MemberResponse, SignupRequest, SignupResponse } from "@/types/api";

export function signup(request: SignupRequest): Promise<SignupResponse> {
  return apiFetch<SignupResponse>("/api/v1/auth/signup", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export async function login(request: LoginRequest): Promise<MemberResponse> {
  const response = await apiFetchRaw("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify(request),
  });

  // BE는 로그인 성공 시 JSESSIONID 하나만 Set-Cookie로 내려준다(다른 쿠키를 추가하면 이 파싱도 같이 봐야 함).
  const setCookie = response.headers.get("set-cookie");
  const sessionId = setCookie?.match(/JSESSIONID=([^;]+)/)?.[1];
  if (sessionId) {
    await setSessionCookie(sessionId);
  }

  return unwrapApiResponse<MemberResponse>(response);
}

export async function logout(): Promise<void> {
  try {
    await apiFetch<void>("/api/v1/auth/logout", { method: "POST" });
  } finally {
    // BE 호출이 실패하더라도(네트워크 오류 등) 로컬 쿠키는 지워서 "로그아웃은 됐다"를 보장한다.
    await clearSessionCookie();
  }
}

// 로그인 여부를 확인할 때 쓴다 — 세션이 없거나 만료됐으면 null.
export async function getMe(): Promise<MemberResponse | null> {
  try {
    return await apiFetch<MemberResponse>("/api/v1/members/me");
  } catch (error) {
    if (error instanceof ApiError && error.code === "UNAUTHORIZED") {
      return null;
    }
    throw error;
  }
}
