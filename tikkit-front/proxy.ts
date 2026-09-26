import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { SESSION_COOKIE_NAME } from "@/lib/session";

// Next 16부터 middleware.ts는 proxy.ts로 이름이 바뀌었다(기능은 동일).
//
// 여기서는 세션 쿠키가 "있는지"만 본다(공식 문서가 명시하는 "낙관적 확인" 용도) — 실제로 아직 유효한지
// (BE에서 만료됐는지 등)는 확인하지 않는다. proxy는 느린 조회(BE 호출)를 하면 안 되므로, 진짜 인증 검증은
// 언제나 각 페이지가 BE를 호출할 때 401로 판단하게 둔다.
export function proxy(request: NextRequest) {
  const hasSession = request.cookies.has(SESSION_COOKIE_NAME);

  if (!hasSession) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", request.nextUrl.pathname + request.nextUrl.search);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/booking/:path*", "/my/:path*"],
};
