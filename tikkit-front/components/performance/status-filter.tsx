import Link from "next/link";
import { cn } from "cn";
import { STATUS_LABELS, STATUS_OPTIONS } from "@/lib/performance-labels";
import { withSearchParams } from "@/lib/url";
import type { PerformanceStatus } from "@/types/api";

interface StatusFilterProps {
  currentSearchParams: Record<string, string | string[] | undefined>;
  activeStatus?: PerformanceStatus;
}

export function StatusFilter({ currentSearchParams, activeStatus }: StatusFilterProps) {
  const options: { label: string; status?: PerformanceStatus }[] = [
    { label: "전체", status: undefined },
    ...STATUS_OPTIONS.map((status) => ({ label: STATUS_LABELS[status], status })),
  ];

  return (
    <div className="flex flex-wrap gap-1.5" role="group" aria-label="판매 상태 필터">
      {options.map((option) => {
        const isActive = option.status === activeStatus;
        return (
          <Link
            key={option.label}
            href={withSearchParams(currentSearchParams, { status: option.status, page: undefined })}
            className={cn(
              "rounded-md border px-2.5 py-1 text-xs font-medium transition-colors",
              isActive
                ? "border-primary text-primary"
                : "border-border text-muted-foreground hover:text-foreground"
            )}
            aria-current={isActive ? "true" : undefined}
          >
            {option.label}
          </Link>
        );
      })}
    </div>
  );
}
