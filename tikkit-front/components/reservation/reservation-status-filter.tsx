import Link from "next/link";
import { cn, focusRing } from "@/lib/utils";
import { RESERVATION_STATUS_LABELS, RESERVATION_STATUS_OPTIONS } from "@/lib/reservation-labels";
import { withSearchParams } from "@/lib/url";
import type { ReservationStatus } from "@/types/api";

interface ReservationStatusFilterProps {
  currentSearchParams: Record<string, string | string[] | undefined>;
  activeStatus?: ReservationStatus;
}

// components/performance/status-filter.tsx와 같은 구조. 필터를 바꾸면 page는 0으로 돌아가야 해서 함께 지운다.
export function ReservationStatusFilter({
  currentSearchParams,
  activeStatus,
}: ReservationStatusFilterProps) {
  const options: { label: string; status?: ReservationStatus }[] = [
    { label: "전체", status: undefined },
    ...RESERVATION_STATUS_OPTIONS.map((status) => ({
      label: RESERVATION_STATUS_LABELS[status],
      status,
    })),
  ];

  return (
    <div className="flex flex-wrap gap-1.5" role="group" aria-label="예매 상태 필터">
      {options.map((option) => {
        const isActive = option.status === activeStatus;
        return (
          <Link
            key={option.label}
            href={withSearchParams(currentSearchParams, { status: option.status, page: undefined })}
            className={cn(
              "rounded-md border px-2.5 py-1.5 text-xs font-medium transition-colors",
              focusRing,
              isActive
                ? "border-primary text-primary"
                : "border-border text-muted-foreground hover:text-foreground"
            )}
            aria-current={isActive ? "page" : undefined}
          >
            {option.label}
          </Link>
        );
      })}
    </div>
  );
}
