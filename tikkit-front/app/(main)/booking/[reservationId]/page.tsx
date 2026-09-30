import Link from "next/link";
import { redirect } from "next/navigation";
import { OrderSummary } from "@/components/booking/order-summary";
import { PaymentForm } from "@/components/booking/payment-form";
import { buttonVariants } from "@/components/ui/button";
import { getRemainingMs, loadReservation } from "@/lib/load-reservation";
import { cn } from "cn";

export default async function BookingPage({
  params,
}: {
  params: Promise<{ reservationId: string }>;
}) {
  const { reservationId } = await params;
  const reservation = await loadReservation(reservationId);

  if (reservation.status === "CONFIRMED") {
    redirect(`/booking/${reservation.id}/complete`);
  }

  const remainingMs = getRemainingMs(reservation.expiresAt);

  // BE 만료 배치는 최대 60초 늦게 돌기 때문에, status가 아직 PENDING이어도 expiresAt이 지났으면 만료로 본다.
  const ended =
    reservation.status === "CANCELLED" ||
    reservation.status === "EXPIRED" ||
    remainingMs <= 0;

  if (ended) {
    return (
      <main className="mx-auto flex w-full max-w-lg flex-1 flex-col items-center justify-center gap-4 px-4 py-16 text-center">
        <h1 className="text-xl font-bold">
          {reservation.status === "CANCELLED" ? "취소된 예약입니다" : "선점 시간이 만료되었습니다"}
        </h1>
        <p className="text-sm text-muted-foreground">
          {reservation.performanceTitle} 예약({reservation.reservationNo})은 더 이상 결제할 수 없습니다.
          공연 페이지에서 다시 예매해주세요.
        </p>
        <Link href="/performances" className={cn(buttonVariants({ size: "lg" }))}>
          공연 목록으로
        </Link>
      </main>
    );
  }

  return (
    // pb-64: 모바일 하단 고정 결제 버튼(에러 메시지 포함)이 내용을 가리지 않게 여유를 둔다
    <main className="mx-auto flex w-full max-w-lg flex-1 flex-col gap-6 px-4 pt-8 pb-64 md:pb-8">
      <h1 className="text-2xl font-bold">결제하기</h1>
      <OrderSummary reservation={reservation} />
      <PaymentForm
        reservationId={reservation.id}
        totalAmount={reservation.totalAmount}
        initialRemainingMs={remainingMs}
      />
    </main>
  );
}
