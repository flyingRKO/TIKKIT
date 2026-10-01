import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { formatDateTime } from "@/lib/format-date";
import { GRADE_LABELS } from "@/lib/performance-labels";
import {
  RESERVATION_STATUS_BADGE_VARIANTS,
  RESERVATION_STATUS_LABELS,
} from "@/lib/reservation-labels";
import type { ReservationSummaryResponse } from "@/types/api";

// 목록 한 줄. 카드 전체가 상세 페이지 링크다. 상호작용이 링크뿐이라 Server Component로 둔다.
export function ReservationListItem({ reservation }: { reservation: ReservationSummaryResponse }) {
  return (
    <li>
      <Link
        href={`/my/reservations/${reservation.id}`}
        className="flex flex-col gap-2 rounded-xl border p-4 transition-colors hover:bg-muted/40 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
      >
        <div className="flex items-start justify-between gap-2">
          <p className="min-w-0 break-words font-semibold">{reservation.performanceTitle}</p>
          <Badge variant={RESERVATION_STATUS_BADGE_VARIANTS[reservation.status]}>
            {RESERVATION_STATUS_LABELS[reservation.status]}
          </Badge>
        </div>
        <p className="text-sm text-muted-foreground">{formatDateTime(reservation.scheduleShowAt)}</p>
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">
            {GRADE_LABELS[reservation.grade]} · {reservation.quantity}매
          </span>
          <span className="font-bold">{reservation.totalAmount.toLocaleString("ko-KR")}원</span>
        </div>
        <p className="text-xs text-muted-foreground">예약번호 {reservation.reservationNo}</p>
      </Link>
    </li>
  );
}
