import Link from "next/link";
import { MobileNav } from "@/components/layout/mobile-nav";
import { ThemeToggle } from "@/components/layout/theme-toggle";

const NAV_ITEMS = [
  { href: "/performances", label: "공연 목록" },
  { href: "/my/reservations", label: "마이페이지" },
];

export function Header() {
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
          {/* 로그인 상태 표시는 Task 011에서 실제 세션 값으로 교체한다 */}
          <Link
            href="/login"
            className="text-sm font-medium text-muted-foreground hover:text-foreground"
          >
            로그인
          </Link>
          <ThemeToggle />
        </nav>

        <div className="flex items-center gap-1 md:hidden">
          <ThemeToggle />
          <MobileNav items={[...NAV_ITEMS, { href: "/login", label: "로그인" }]} />
        </div>
      </div>
    </header>
  );
}
