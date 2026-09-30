import { CheckCircle2 } from "lucide-react";
import Link from "next/link";
import { redirect } from "next/navigation";
import { OrderSummary } from "@/components/booking/order-summary";
import { buttonVariants } from "@/components/ui/button";
import { PAYMENT_METHOD_LABELS } from "@/lib/actions/reservation-state";
import { formatDateTime } from "@/lib/format-date";
import { loadReservation } from "@/lib/load-reservation";
import { cn } from "cn";

export default async function BookingCompletePage({
  params,
}: {
  params: Promise<{ reservationId: string }>;
}) {
  const { reservationId } = await params;
  const reservation = await loadReservation(reservationId);

  // 결제가 끝난 예약만 완료 화면을 보여준다. 주소를 직접 쳐서 들어온 경우엔 결제 페이지가 상태별로 안내한다.
  if (reservation.status !== "CONFIRMED") {
    redirect(`/booking/${reservation.id}`);
  }

  const { payment } = reservation;

  return (
    <main className="mx-auto flex w-full max-w-lg flex-1 flex-col gap-6 px-4 py-8">
      <div className="flex flex-col items-center gap-2 text-center">
        <CheckCircle2 className="size-12 text-primary" aria-hidden="true" />
        <h1 className="text-2xl font-bold">예매가 완료되었습니다</h1>
        <p className="text-sm text-muted-foreground">예약번호 {reservation.reservationNo}</p>
      </div>

      <OrderSummary reservation={reservation} />

      {payment && (
        <section className="flex flex-col gap-3 rounded-xl border p-4">
          <h2 className="text-sm font-semibold">결제 정보</h2>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
            <dt className="text-muted-foreground">결제수단</dt>
            <dd className="text-right">{PAYMENT_METHOD_LABELS[payment.method]}</dd>
            {payment.paidAt && (
              <>
                <dt className="text-muted-foreground">결제 일시</dt>
                <dd className="text-right">{formatDateTime(payment.paidAt)}</dd>
              </>
            )}
          </dl>
        </section>
      )}

      <div className="flex flex-col gap-2 sm:flex-row">
        <Link
          href="/my/reservations"
          className={cn(buttonVariants({ size: "lg" }), "w-full sm:flex-1")}
        >
          예매 내역 보기
        </Link>
        <Link
          href="/"
          className={cn(buttonVariants({ variant: "outline", size: "lg" }), "w-full sm:flex-1")}
        >
          홈으로
        </Link>
      </div>
    </main>
  );
}
