import Link from "next/link";
import { AuthStatus } from "@/components/layout/auth-status";
import { MobileNav } from "@/components/layout/mobile-nav";
import { ThemeToggle } from "@/components/layout/theme-toggle";
import { getMe } from "@/lib/api/auth";

const NAV_ITEMS = [
  { href: "/performances", label: "공연 목록" },
  { href: "/my/reservations", label: "마이페이지" },
];

// 로그인 상태를 보여주려고 매 요청마다 BE에 /members/me를 한 번 물어본다. 이 프로젝트에 아직 세션 캐시가
// 없어서(그런 캐시를 두면 로그아웃/만료가 늦게 반영될 수 있음) 정확성을 우선한 선택이다 — 캐싱은 Task 028 몫.
export async function Header() {
  const member = await getMe();

  return (
    <header className="sticky top-0 z-30 border-b bg-background/80 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-4">
        <Link
          href="/"
          className="bg-linear-to-r from-primary to-highlight bg-clip-text text-lg font-bold text-transparent"
        >
          TIKKIT
        </Link>

        <nav className="hidden items-center gap-4 md:flex">
          {NAV_ITEMS.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="text-sm font-medium text-muted-foreground hover:text-foreground"
            >
              {item.label}
            </Link>
          ))}
          <AuthStatus member={member} />
          <ThemeToggle />
        </nav>

        <div className="flex items-center gap-1 md:hidden">
          <ThemeToggle />
          <MobileNav items={NAV_ITEMS} authSlot={<AuthStatus member={member} />} />
        </div>
      </div>
    </header>
  );
}
