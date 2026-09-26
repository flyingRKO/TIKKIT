import Link from "next/link";
import { logoutAction } from "@/lib/actions/auth";
import { cn } from "cn";
import type { MemberResponse } from "@/types/api";

interface AuthStatusProps {
  member: MemberResponse | null;
  className?: string;
}

const LINK_CLASS = "text-sm font-medium text-muted-foreground hover:text-foreground";

export function AuthStatus({ member, className }: AuthStatusProps) {
  if (!member) {
    return (
      <Link href="/login" className={cn(LINK_CLASS, className)}>
        로그인
      </Link>
    );
  }

  return (
    <form action={logoutAction} className={cn("flex items-center gap-2", className)}>
      <span className="text-sm text-muted-foreground">{member.name}님</span>
      <button type="submit" className={LINK_CLASS}>
        로그아웃
      </button>
    </form>
  );
}
