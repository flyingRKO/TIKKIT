"use client";

import Link from "next/link";
import { useActionState, useState } from "react";
import { CountdownTimer } from "@/components/booking/countdown-timer";
import { FormError } from "@/components/layout/form-error";
import { StickyCta } from "@/components/layout/sticky-cta";
import { Button, buttonVariants } from "@/components/ui/button";
import { payReservationAction } from "@/lib/actions/reservation";
import {
  initialReservationActionState,
  PAYMENT_METHOD_LABELS,
  PAYMENT_METHODS,
} from "@/lib/actions/reservation-state";
import { cn } from "cn";
import type { PaymentMethod } from "@/types/api";

interface PaymentFormProps {
  reservationId: number;
  totalAmount: number;
  initialRemainingMs: number;
}

export function PaymentForm({ reservationId, totalAmount, initialRemainingMs }: PaymentFormProps) {
  const [method, setMethod] = useState<PaymentMethod>("CARD");
  // 0초가 되면 화면에서만 만료 처리한다. cancel API는 부르지 않고, 재고 복원은 BE 만료 배치(최대 60초 지연)에 맡긴다.
  const [expired, setExpired] = useState(initialRemainingMs <= 0);
  const [state, formAction, pending] = useActionState(
    payReservationAction,
    initialReservationActionState
  );

  return (
    <form action={formAction} className="flex flex-col gap-6">
      <input type="hidden" name="reservationId" value={reservationId} />

      {expired ? (
        <div role="alert" className="rounded-xl border border-destructive/50 bg-destructive/5 p-4 text-sm">
          선점 시간이 만료되었습니다. 공연 페이지에서 다시 예매해주세요.
        </div>
      ) : (
        <CountdownTimer initialRemainingMs={initialRemainingMs} onExpire={() => setExpired(true)} />
      )}

      <fieldset className="flex flex-col gap-2" disabled={expired || pending}>
        <legend className="mb-2 text-sm font-semibold">결제수단 선택</legend>
        {PAYMENT_METHODS.map((option) => (
          <label
            key={option}
            className={cn(
              "flex cursor-pointer items-center gap-3 rounded-md border px-3 py-3 text-sm transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring",
              method === option ? "border-primary bg-primary/5" : "border-border",
              (expired || pending) && "cursor-not-allowed opacity-50"
            )}
          >
            <input
              type="radio"
              name="method"
              value={option}
              checked={method === option}
              onChange={() => setMethod(option)}
              className="accent-primary"
            />
            {PAYMENT_METHOD_LABELS[option]}
          </label>
        ))}
        <p className="text-xs text-muted-foreground">모의 결제입니다. 실제로 금액이 청구되지 않습니다.</p>
      </fieldset>


      <StickyCta>
        <FormError message={state.error} />
        <div className="flex items-center justify-between">
          <span className="text-sm text-muted-foreground">결제 금액</span>
          <span className="text-lg font-bold">{totalAmount.toLocaleString("ko-KR")}원</span>
        </div>
        {expired ? (
          <Link href="/performances" className={cn(buttonVariants({ size: "lg" }), "w-full")}>
            공연 목록으로
          </Link>
        ) : (
          <Button type="submit" size="lg" className="w-full" disabled={pending}>
            {pending ? "결제 중..." : `${totalAmount.toLocaleString("ko-KR")}원 결제하기`}
          </Button>
        )}
      </StickyCta>
    </form>
  );
}
