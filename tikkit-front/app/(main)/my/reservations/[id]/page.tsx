import Link from "next/link";
import { OrderSummary } from "@/components/booking/order-summary";
import { CancelReservationButton } from "@/components/reservation/cancel-reservation-button";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { PAYMENT_METHOD_LABELS } from "@/lib/actions/reservation-state";
import { formatDateTime } from "@/lib/format-date";
import { loadReservation } from "@/lib/load-reservation";
import { resolveReservationView } from "@/lib/reservation-rules";
import {
  PAYMENT_STATUS_LABELS,
  RESERVATION_STATUS_BADGE_VARIANTS,
  RESERVATION_STATUS_LABELS,
} from "@/lib/reservation-labels";
import { cn, focusRing } from "@/lib/utils";

export default async function MyReservationDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const reservation = await loadReservation(id, `/my/reservations/${id}`);
  const view = resolveReservationView(reservation);
  const { payment } = reservation;

  return (
    <main className="mx-auto flex w-full max-w-lg flex-1 flex-col gap-6 px-4 py-8">
      <div className="flex flex-col gap-3">
        <Link
          href="/my/reservations"
          className={cn("text-sm text-muted-foreground hover:text-foreground", focusRing)}
        >
          ← 예매 내역
        </Link>
        <div className="flex items-center gap-2">
          <h1 className="text-2xl font-bold">예매 상세</h1>
          <Badge variant={RESERVATION_STATUS_BADGE_VARIANTS[view.status]}>
            {RESERVATION_STATUS_LABELS[view.status]}
          </Badge>
        </div>
      </div>

      <OrderSummary reservation={reservation} />

      {(payment || reservation.cancelledAt) && (
        <section className="flex flex-col gap-3 rounded-xl border p-4">
          <h2 className="text-sm font-semibold">결제 정보</h2>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
            {payment && (
              <>
                <dt className="text-muted-foreground">결제수단</dt>
                <dd className="text-right">{PAYMENT_METHOD_LABELS[payment.method]}</dd>
                <dt className="text-muted-foreground">결제 상태</dt>
                <dd className="text-right">{PAYMENT_STATUS_LABELS[payment.status]}</dd>
                {payment.paidAt && (
                  <>
                    <dt className="text-muted-foreground">결제 일시</dt>
                    <dd className="text-right">{formatDateTime(payment.paidAt)}</dd>
                  </>
                )}
              </>
            )}
            {reservation.cancelledAt && (
              <>
                <dt className="text-muted-foreground">취소 일시</dt>
                <dd className="text-right">{formatDateTime(reservation.cancelledAt)}</dd>
              </>
            )}
          </dl>
        </section>
      )}

      {view.notice && (
        <p role="status" className="rounded-xl border bg-muted/40 p-4 text-sm text-muted-foreground">
          {view.notice}
        </p>
      )}

      {(view.canPay || view.canCancel) && (
        <div className="flex flex-col gap-2">
          {view.canPay && (
            <Link
              href={`/booking/${reservation.id}`}
              className={cn(buttonVariants({ size: "lg" }), "w-full")}
            >
              결제하러 가기
            </Link>
          )}
          {view.canCancel && (
            <CancelReservationButton
              reservationId={reservation.id}
              isPaid={view.status === "CONFIRMED"}
              totalAmount={reservation.totalAmount}
            />
          )}
        </div>
      )}
    </main>
  );
}
