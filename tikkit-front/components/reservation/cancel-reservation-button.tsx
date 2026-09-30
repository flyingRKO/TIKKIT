"use client";

import { useActionState } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { cancelReservationAction } from "@/lib/actions/reservation";
import { initialReservationActionState } from "@/lib/actions/reservation-state";

interface CancelReservationButtonProps {
  reservationId: number;
  // CONFIRMED면 환불 안내, PENDING이면 선점 해제 안내로 문구가 갈린다
  isPaid: boolean;
  totalAmount: number;
}

export function CancelReservationButton({
  reservationId,
  isPaid,
  totalAmount,
}: CancelReservationButtonProps) {
  const [state, formAction, pending] = useActionState(
    cancelReservationAction,
    initialReservationActionState
  );

  return (
    <AlertDialog>
      <AlertDialogTrigger
        render={<Button type="button" variant="outline" size="lg" className="w-full" />}
      >
        예매 취소
      </AlertDialogTrigger>
      <AlertDialogContent>
        {/* Popup의 grid 레이아웃이 깨지지 않게 form은 레이아웃에 참여하지 않도록(contents) 둔다 */}
        <form action={formAction} className="contents">
          <input type="hidden" name="reservationId" value={reservationId} />
          <AlertDialogHeader>
            <AlertDialogTitle>예매를 취소할까요?</AlertDialogTitle>
            <AlertDialogDescription>
              {isPaid
                ? `결제 금액 ${totalAmount.toLocaleString("ko-KR")}원이 전액 환불됩니다. `
                : "선점한 좌석이 해제됩니다. "}
              취소한 예매는 되돌릴 수 없습니다.
            </AlertDialogDescription>
            {state.error && (
              <p role="alert" className="text-sm text-destructive">
                {state.error}
              </p>
            )}
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={pending}>돌아가기</AlertDialogCancel>
            <AlertDialogAction type="submit" variant="destructive" disabled={pending}>
              {pending ? "취소 중..." : "취소하기"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
