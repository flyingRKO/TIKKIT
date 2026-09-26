"use client";

import { Menu, X } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";
import { useState } from "react";
import { Button } from "@/components/ui/button";

interface NavItem {
  href: string;
  label: string;
}

export function MobileNav({ items, authSlot }: { items: NavItem[]; authSlot?: ReactNode }) {
  const [open, setOpen] = useState(false);

  return (
    <div className="md:hidden">
      <Button
        variant="ghost"
        size="icon"
        aria-label={open ? "메뉴 닫기" : "메뉴 열기"}
        onClick={() => setOpen((prev) => !prev)}
      >
        {open ? <X /> : <Menu />}
      </Button>
      {open && (
        <nav className="absolute inset-x-0 top-14 z-30 flex flex-col gap-1 border-b bg-background p-4 shadow-sm">
          {items.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="rounded-md px-3 py-2 text-sm font-medium text-foreground hover:bg-muted"
              onClick={() => setOpen(false)}
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
