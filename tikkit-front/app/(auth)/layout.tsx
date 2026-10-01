import Link from "next/link";
import type { ReactNode } from "react";
import { cn, focusRing } from "@/lib/utils";

// (main)과 달리 페이지가 자기 <main>을 갖지 않아서 레이아웃이 main 랜드마크를 맡는다.
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-8 px-4 py-12">
      <Link
        href="/"
        className={cn(
          "bg-linear-to-r from-primary to-highlight bg-clip-text text-2xl font-bold text-transparent",
          focusRing
        )}
      >
        TIKKIT
      </Link>
      <div className="w-full max-w-sm">{children}</div>
    </main>
  );
}
