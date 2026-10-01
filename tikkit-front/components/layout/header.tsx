import Link from "next/link";
import { AuthStatus } from "@/components/layout/auth-status";
import { MobileNav } from "@/components/layout/mobile-nav";
import { ThemeToggle } from "@/components/layout/theme-toggle";
import { getMe } from "@/lib/api/auth";
import { ApiError, INVALID_RESPONSE_CODE, NETWORK_ERROR_CODE } from "@/lib/api/client";
import { cn, focusRing } from "@/lib/utils";
import type { MemberResponse } from "@/types/api";

const NAV_ITEMS = [
  { href: "/performances", label: "공연 목록" },
  { href: "/my/reservations", label: "마이페이지" },
];

// 로그인 상태를 보여주려고 매 요청마다 BE에 /members/me를 한 번 물어본다. 이 프로젝트에 아직 세션 캐시가
// 없어서(그런 캐시를 두면 로그아웃/만료가 늦게 반영될 수 있음) 정확성을 우선한 선택이다 — 캐싱은 Task 028 몫.
//
// BE에 연결이 안 되면 로그인 여부를 알 수 없어서 로그인 영역만 빼고 나머지 헤더는 그대로 보여준다.
// layout에서 던진 에러는 (main)/error.tsx가 못 받기 때문에(같은 세그먼트의 layout은 감싸지 못함) 여기서 흡수한다.
async function getMemberOrUnknown(): Promise<MemberResponse | null | undefined> {
  try {
    return await getMe();
  } catch (error) {
    if (
      error instanceof ApiError &&
      (error.code === NETWORK_ERROR_CODE || error.code === INVALID_RESPONSE_CODE)
    ) {
      return undefined;
    }
    throw error;
  }
}

export async function Header() {
  const member = await getMemberOrUnknown();
  const authStatus = member === undefined ? null : <AuthStatus member={member} />;

  return (
    <header className="sticky top-0 z-30 border-b bg-background/80 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-4">
        <Link
          href="/"
          className={cn(
            "bg-linear-to-r from-primary to-highlight bg-clip-text text-lg font-bold text-transparent",
            focusRing
          )}
        >
          TIKKIT
        </Link>

        <nav className="hidden items-center gap-4 md:flex">
          {NAV_ITEMS.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "text-sm font-medium text-muted-foreground hover:text-foreground",
                focusRing
              )}
            >
              {item.label}
            </Link>
          ))}
          {authStatus}
          <ThemeToggle />
        </nav>

        <div className="flex items-center gap-1 md:hidden">
          <ThemeToggle />
          <MobileNav items={NAV_ITEMS} authSlot={authStatus} />
        </div>
      </div>
    </header>
  );
}
