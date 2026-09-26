"use server";

import { redirect } from "next/navigation";
import { login, logout, signup } from "@/lib/api/auth";
import { ApiError } from "@/lib/api/client";
import type { AuthActionState } from "@/lib/actions/auth-state";
import type { SignupRequest } from "@/types/api";

// ?redirect= 값은 사용자가 URL로 직접 조작할 수 있다. "/"로 시작하지 않거나 "//..."(다른 호스트로 취급될 수 있는
// 프로토콜 상대 경로)면 무시한다 — 그대로 redirect()에 넘기면 로그인 직후 외부 사이트로 보내는 오픈 리다이렉트가 된다.
function safeRedirectTarget(value: FormDataEntryValue | null): string {
  const target = typeof value === "string" ? value : "";
  if (!target.startsWith("/") || target.startsWith("//")) {
    return "/";
  }
  return target;
}

function toErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.errors.length > 0 ? error.errors.join(" ") : error.message;
  }
  throw error;
}

export async function loginAction(
  _prevState: AuthActionState,
  formData: FormData
): Promise<AuthActionState> {
  const email = String(formData.get("email") ?? "").trim();
  const password = String(formData.get("password") ?? "");
  const redirectTo = safeRedirectTarget(formData.get("redirect"));

  try {
    await login({ email, password });
  } catch (error) {
    return { error: toErrorMessage(error) };
  }

  redirect(redirectTo);
}

export async function signupAction(
  _prevState: AuthActionState,
  formData: FormData
): Promise<AuthActionState> {
  const request: SignupRequest = {
    email: String(formData.get("email") ?? "").trim(),
    password: String(formData.get("password") ?? ""),
    name: String(formData.get("name") ?? "").trim(),
    phone: String(formData.get("phone") ?? "").trim(),
  };
  const redirectTo = safeRedirectTarget(formData.get("redirect"));

  try {
    await signup(request);
  } catch (error) {
    return { error: toErrorMessage(error) };
  }

  // 가입과 로그인은 분리한다 — 가입 후 로그인 화면으로 보내고, redirect는 그대로 물려줘서
  // 로그인까지 마치면 원래 가려던 곳으로 이어지게 한다.
  redirect(`/login?redirect=${encodeURIComponent(redirectTo)}`);
}

export async function logoutAction(): Promise<void> {
  await logout();
  redirect("/");
}
