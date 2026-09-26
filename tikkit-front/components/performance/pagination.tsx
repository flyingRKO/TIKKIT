import Link from "next/link";
import { cn } from "cn";
import { withSearchParams } from "@/lib/url";

interface PaginationProps {
  currentSearchParams: Record<string, string | string[] | undefined>;
  page: number; // 0-based (PageResponse.page과 동일)
  totalPages: number;
}

export function Pagination({ currentSearchParams, page, totalPages }: PaginationProps) {
  if (totalPages <= 1) return null;

  const hasPrev = page > 0;
  const hasNext = page < totalPages - 1;

  return (
    <nav className="flex items-center justify-center gap-4" aria-label="페이지네이션">
      <PageLink
        currentSearchParams={currentSearchParams}
        page={page - 1}
        disabled={!hasPrev}
        label="이전"
      />
      <span className="text-sm text-muted-foreground">
        {page + 1} / {totalPages}
      </span>
      <PageLink
        currentSearchParams={currentSearchParams}
        page={page + 1}
        disabled={!hasNext}
        label="다음"
      />
    </nav>
  );
}

function PageLink({
  currentSearchParams,
  page,
  disabled,
  label,
}: {
  currentSearchParams: Record<string, string | string[] | undefined>;
  page: number;
  disabled: boolean;
  label: string;
}) {
  const className = cn(
    "rounded-md px-3 py-1.5 text-sm font-medium",
    disabled ? "pointer-events-none text-muted-foreground/50" : "hover:bg-muted"
  );

  if (disabled) {
    return <span className={className}>{label}</span>;
  }

  return (
    <Link
      href={withSearchParams(currentSearchParams, { page: String(page) })}
      className={className}
    >
      {label}
    </Link>
  );
}
