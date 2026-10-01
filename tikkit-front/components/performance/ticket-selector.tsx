"use client";

import { useActionState, useState } from "react";
import { FormError } from "@/components/layout/form-error";
import { Button } from "@/components/ui/button";
import { createReservationAction } from "@/lib/actions/reservation";
import { initialReservationActionState } from "@/lib/actions/reservation-state";
import { formatDateTime } from "@/lib/format-date";
import { GRADE_LABELS } from "@/lib/performance-labels";
import { cn, focusRing } from "@/lib/utils";
import type { ScheduleSummaryResponse, TicketGradeResponse } from "@/types/api";

const MAX_QUANTITY = 4;

type SaleState = "BEFORE_OPEN" | "OPEN" | "CLOSED";

// 회차별 판매 기간 판단. 화면에서 미리 막아주는 용도일 뿐이고, 최종 판단은 BE(BOOKING_NOT_OPEN)가 한다.
function getSaleState(schedule: ScheduleSummaryResponse, now: number): SaleState {
  if (now < new Date(schedule.bookingOpenAt).getTime()) return "BEFORE_OPEN";
  if (now >= new Date(schedule.bookingCloseAt).getTime()) return "CLOSED";
  return "OPEN";
}

interface TicketSelectorProps {
  performanceId: number;
  performanceTitle: string;
  schedules: ScheduleSummaryResponse[];
  ticketGradesBySchedule: Record<number, TicketGradeResponse[]>;
}

export function TicketSelector({
  performanceId,
  performanceTitle,
  schedules,
  ticketGradesBySchedule,
}: TicketSelectorProps) {
  // 렌더마다 Date.now()를 부르면 값이 계속 바뀌어서, 마운트 시점 값을 한 번만 잡아둔다.
  const [now] = useState(() => Date.now());
  const [scheduleId, setScheduleId] = useState<number | null>(
    () => (schedules.find((s) => getSaleState(s, now) === "OPEN") ?? schedules[0])?.id ?? null
  );
  const [gradeId, setGradeId] = useState<number | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [state, formAction, pending] = useActionState(
    createReservationAction,
    initialReservationActionState
  );

  const selectedSchedule = schedules.find((schedule) => schedule.id === scheduleId) ?? null;
  const grades = scheduleId ? ticketGradesBySchedule[scheduleId] ?? [] : [];
  const selectedGrade = grades.find((grade) => grade.id === gradeId) ?? null;
  const totalPrice = selectedGrade ? selectedGrade.price * quantity : 0;
  const canBook =
    selectedSchedule !== null &&
    selectedGrade !== null &&
    getSaleState(selectedSchedule, now) === "OPEN";

  function handleSelectSchedule(nextScheduleId: number) {
    setScheduleId(nextScheduleId);
    setGradeId(null);
    setQuantity(1);
  }

  function handleSelectGrade(grade: TicketGradeResponse) {
    setGradeId(grade.id);
    setQuantity(1);
  }

  if (schedules.length === 0) {
    return <p className="text-sm text-muted-foreground">예매 가능한 회차가 없습니다.</p>;
  }

  return (
    <form action={formAction} className="flex flex-col gap-5 rounded-xl border p-4">
      <input type="hidden" name="performanceId" value={performanceId} />
      <input type="hidden" name="performanceTitle" value={performanceTitle} />
      <input type="hidden" name="scheduleId" value={scheduleId ?? ""} />
      <input type="hidden" name="showAt" value={selectedSchedule?.showAt ?? ""} />
      <input type="hidden" name="ticketGradeId" value={gradeId ?? ""} />
      <input type="hidden" name="grade" value={selectedGrade?.grade ?? ""} />
      <input type="hidden" name="quantity" value={quantity} />

      <section className="flex flex-col gap-2">
        <h2 className="text-sm font-semibold">회차 선택</h2>
        <div className="flex flex-col gap-1.5">
          {schedules.map((schedule) => {
            const saleState = getSaleState(schedule, now);
            return (
              <button
                key={schedule.id}
                type="button"
                disabled={saleState !== "OPEN"}
                onClick={() => handleSelectSchedule(schedule.id)}
                className={cn(
                  "flex flex-col rounded-md border px-3 py-2 text-left text-sm transition-colors disabled:cursor-not-allowed disabled:opacity-50",
                  focusRing,
                  scheduleId === schedule.id
                    ? "border-primary bg-primary/5 text-foreground"
                    : "border-border text-muted-foreground hover:text-foreground"
                )}
                aria-pressed={scheduleId === schedule.id}
              >
                <span>{formatDateTime(schedule.showAt)}</span>
                {saleState === "BEFORE_OPEN" && (
                  <span className="text-xs">{formatDateTime(schedule.bookingOpenAt)} 예매 오픈</span>
                )}
                {saleState === "CLOSED" && <span className="text-xs">예매 종료</span>}
              </button>
            );
          })}
        </div>
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="text-sm font-semibold">등급 선택</h2>
        {grades.length === 0 ? (
          <p className="text-sm text-muted-foreground">판매 중인 등급이 없습니다.</p>
        ) : (
          <div className="flex flex-col gap-1.5">
            {grades.map((grade) => {
              const soldOut = grade.remainingQuantity === 0;
              return (
                <button
                  key={grade.id}
                  type="button"
                  disabled={soldOut}
                  onClick={() => handleSelectGrade(grade)}
                  className={cn(
                    "flex items-center justify-between rounded-md border px-3 py-2 text-sm transition-colors disabled:cursor-not-allowed disabled:opacity-50",
                    focusRing,
                    gradeId === grade.id
                      ? "border-primary bg-primary/5"
                      : "border-border hover:text-foreground"
                  )}
                  aria-pressed={gradeId === grade.id}
                >
                  <span>{GRADE_LABELS[grade.grade]}</span>
                  <span className="text-muted-foreground">
                    {soldOut ? "매진" : `${grade.price.toLocaleString()}원 · 잔여 ${grade.remainingQuantity}석`}
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </section>

      {selectedGrade && (
        <section className="flex items-center justify-between">
          <h2 className="text-sm font-semibold">수량</h2>
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={() => setQuantity((q) => Math.max(1, q - 1))}
              disabled={quantity <= 1}
              aria-label="수량 감소"
            >
              -
            </Button>
            <span className="w-6 text-center text-sm">{quantity}</span>
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={() =>
                setQuantity((q) => Math.min(MAX_QUANTITY, selectedGrade.remainingQuantity, q + 1))
              }
              disabled={quantity >= MAX_QUANTITY || quantity >= selectedGrade.remainingQuantity}
              aria-label="수량 증가"
            >
              +
            </Button>
          </div>
        </section>
      )}

      {/* 모바일은 화면 하단에 고정하고, md 이상에서는 카드 안에 그대로 둔다 */}
      <div className="fixed inset-x-0 bottom-0 z-20 flex flex-col gap-3 border-t bg-background p-4 md:static md:z-auto md:border-t md:bg-transparent md:px-0 md:pb-0">
        <FormError message={state.error} />
        <div className="flex items-center justify-between">
          <span className="text-sm text-muted-foreground">합계</span>
          <span className="text-lg font-bold">{totalPrice.toLocaleString()}원</span>
        </div>
        <Button type="submit" disabled={!canBook || pending} className="w-full" size="lg">
          {pending ? "선점 중..." : "예매하기"}
        </Button>
      </div>
    </form>
  );
}
