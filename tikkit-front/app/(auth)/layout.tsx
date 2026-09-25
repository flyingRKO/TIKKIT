import Link from "next/link";
import type { ReactNode } from "react";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-8 px-4 py-12">
      <Link
        href="/"
        className="bg-linear-to-r from-primary to-highlight bg-clip-text text-2xl font-bold text-transparent"
      >
        TIKKIT
      </Link>
      <div className="w-full max-w-sm">{children}</div>
    </div>
  );
}
