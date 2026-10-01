"use client";

import { Menu, X } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { useEffect, useId, useState } from "react";
import { Button } from "@/components/ui/button";
import { cn, focusRing } from "@/lib/utils";

interface NavItem {
  href: string;
  label: string;
}

export function MobileNav({ items, authSlot }: { items: NavItem[]; authSlot?: ReactNode }) {
  const pathname = usePathname();
  const panelId = useId();
  // open 여부 대신 "메뉴를 연 경로"를 저장한다. 링크 이동이나 로그아웃 리다이렉트로 경로가 바뀌면
  // 따로 닫는 코드 없이 닫힌 상태가 된다.
  const [openedAt, setOpenedAt] = useState<string | null>(null);
  const open = openedAt === pathname;

  // 다른 경로로 넘어갔다가 원래 경로로 돌아왔을 때 메뉴가 저절로 다시 열리지 않게 값을 비운다.
  // (렌더 중 setState는 "props가 바뀔 때 state 조정" 용도로 React 문서가 허용하는 패턴이다.)
  if (openedAt !== null && openedAt !== pathname) {
    setOpenedAt(null);
  }

  useEffect(() => {
    if (!open) return;

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setOpenedAt(null);
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [open]);

  return (
    <div className="md:hidden">
      <Button
        variant="ghost"
        size="icon"
        aria-label={open ? "메뉴 닫기" : "메뉴 열기"}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpenedAt(open ? null : pathname)}
      >
        {open ? <X /> : <Menu />}
      </Button>
      {open && (
        <nav
          id={panelId}
          aria-label="주 메뉴"
          className="absolute inset-x-0 top-14 z-30 flex flex-col gap-1 border-b bg-background p-4 shadow-sm"
        >
          {items.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "rounded-md px-3 py-2 text-sm font-medium text-foreground hover:bg-muted",
                focusRing
              )}
              onClick={() => setOpenedAt(null)}
            >
              {item.label}
            </Link>
          ))}
          {authSlot && <div className="px-3 py-2">{authSlot}</div>}
        </nav>
      )}
    </div>
  );
}
