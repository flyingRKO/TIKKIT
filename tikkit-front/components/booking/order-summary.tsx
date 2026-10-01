import { formatDateTime } from "@/lib/format-date";
import { GRADE_LABELS } from "@/lib/performance-labels";
import type { ReservationDetailResponse } from "@/types/api";

// 결제 페이지·완료 페이지가 같이 쓰는 주문 요약. 상호작용이 없어 Server Component로 둔다.
// 참고: BE 상세 응답에 공연장·포스터가 없어서 공연명/회차/등급/수량/금액만 보여준다.
export function OrderSummary({ reservation }: { reservation: ReservationDetailResponse }) {
  return (
    <section className="flex flex-col gap-3 rounded-xl border p-4">
      <h2 className="text-sm font-semibold">주문 요약</h2>
      <p className="break-words text-lg font-bold">{reservation.performanceTitle}</p>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
        <dt className="text-muted-foreground">예약번호</dt>
        <dd className="min-w-0 break-words text-right">{reservation.reservationNo}</dd>
        <dt className="text-muted-foreground">관람 일시</dt>
        <dd className="text-right">{formatDateTime(reservation.scheduleShowAt)}</dd>
        <dt className="text-muted-foreground">등급</dt>
        <dd className="text-right">{GRADE_LABELS[reservation.grade]}</dd>
        <dt className="text-muted-foreground">단가</dt>
        <dd className="text-right">{reservation.unitPrice.toLocaleString("ko-KR")}원</dd>
        <dt className="text-muted-foreground">수량</dt>
        <dd className="text-right">{reservation.quantity}매</dd>
      </dl>
      <div className="flex items-center justify-between border-t pt-3">
        <span className="text-sm text-muted-foreground">총 결제 금액</span>
        <span className="text-lg font-bold">{reservation.totalAmount.toLocaleString("ko-KR")}원</span>
      </div>
    </section>
  );
}
