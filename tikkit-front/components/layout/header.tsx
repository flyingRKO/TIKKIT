import Link from "next/link";
import { cache, Suspense } from "react";
import { AuthStatus } from "@/components/layout/auth-status";
import { MobileNav } from "@/components/layout/mobile-nav";
import { ThemeToggle } from "@/components/layout/theme-toggle";
import { Skeleton } from "@/components/ui/skeleton";
import { getMe } from "@/lib/api/auth";
import { ApiError, INVALID_RESPONSE_CODE, NETWORK_ERROR_CODE } from "@/lib/api/client";
import { cn, focusRing } from "@/lib/utils";
import type { MemberResponse } from "@/types/api";

const NAV_ITEMS = [
  { href: "/performances", label: "공연 목록" },
  { href: "/my/reservations", label: "마이페이지" },
];

// 로그인 상태를 보여주려고 매 요청마다 BE에 /members/me를 한 번 물어본다. 세션 캐시를 두면
// 로그아웃/만료가 늦게 반영되므로 정확성을 우선한 선택이다.
//
// cache()로 감싸는 이유: 아래에서 데스크톱 nav와 MobileNav 두 자리에 각각 Suspense 경계를
// 두는데, 그러면 AuthSlot이 두 번 렌더된다. fetch 메모이제이션에 기대도 보통 1회로 합쳐지지만
// 그건 URL·옵션이 같을 때의 부수효과라서, 요청 단위 메모이제이션을 명시적으로 건다.
// (호출이 2회로 늘면 그 자체가 회귀다 — actuator http_server_requests 델타로 확인한다.)
//
// BE에 연결이 안 되면 로그인 여부를 알 수 없어서 로그인 영역만 빼고 나머지 헤더는 그대로 보여준다.
// layout에서 던진 에러는 (main)/error.tsx가 못 받기 때문에(같은 세그먼트의 layout은 감싸지 못함)
// 여기서 흡수한다. Suspense 안으로 옮겨도 여전히 layout 세그먼트라 이 흡수는 계속 필요하다.
const getMemberOrUnknown = cache(async (): Promise<MemberResponse | null | undefined> => {
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
});

/**
 * 세션 조회에 의존하는 조각만 떼어낸 것 (Task 030_2).
 *
 * 분리 전에는 Header 전체가 async여서 await getMe()가 끝날 때까지 헤더가 통째로 막혀 있었다.
 * 로고·nav 링크·테마 토글은 세션과 아무 상관이 없는데도 같이 기다렸고, 헤더가 (main) layout에
 * 있으니 그 아래 모든 페이지의 셸이 같이 밀렸다.
 */
async function AuthSlot() {
  const member = await getMemberOrUnknown();
  if (member === undefined) {
    return null;
  }
  return <AuthStatus member={member} />;
}

/** "로그인" 글자 폭에 맞춘 자리표시자. 폭이 크게 다르면 세션이 도착할 때 nav가 밀린다. */
function AuthSlotSkeleton() {
  return <Skeleton className="h-4 w-10" />;
}

export function Header() {
  // 같은 노드를 두 자리에 쓴다. React는 각각을 따로 렌더하지만 데이터 조회는 cache()가 합친다.
  const authSlot = (
    <Suspense fallback={<AuthSlotSkeleton />}>
      <AuthSlot />
    </Suspense>
  );

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
          {authSlot}
          <ThemeToggle />
        </nav>

        <div className="flex items-center gap-1 md:hidden">
          <ThemeToggle />
          <MobileNav items={NAV_ITEMS} authSlot={authSlot} />
        </div>
      </div>
    </header>
  );
}
