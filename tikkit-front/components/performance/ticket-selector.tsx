"use client";

import Link from "next/link";
import { useState } from "react";
import { StickyCta } from "@/components/layout/sticky-cta";
import { Button, buttonVariants } from "@/components/ui/button";
import { formatDateTime } from "@/lib/format-date";
import { GRADE_LABELS } from "@/lib/performance-labels";
import { getSaleState, MAX_QUANTITY } from "@/lib/schedule-rules";
import { cn, focusRing } from "@/lib/utils";
import type { ScheduleSummaryResponse, TicketGradeResponse } from "@/types/api";

interface TicketSelectorProps {
  performanceId: number;
  schedules: ScheduleSummaryResponse[];
  ticketGradesBySchedule: Record<number, TicketGradeResponse[]>;
}

export function TicketSelector({
  performanceId,
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

  const selectedSchedule = schedules.find((schedule) => schedule.id === scheduleId) ?? null;
  const grades = scheduleId ? ticketGradesBySchedule[scheduleId] ?? [] : [];
  const selectedGrade = grades.find((grade) => grade.id === gradeId) ?? null;
  const totalPrice = selectedGrade ? selectedGrade.price * quantity : 0;
  const canBook =
    selectedSchedule !== null &&
    selectedGrade !== null &&
    getSaleState(selectedSchedule, now) === "OPEN";

  // 선점은 좌석 선택 화면에서 일어난다. 등급·매수를 쿼리에 실어 보내면
  // 새로고침·뒤로가기·로그인 복귀에도 그 선택이 남는다.
  const seatsHref =
    selectedSchedule && selectedGrade
      ? `/performances/${performanceId}/schedules/${selectedSchedule.id}/seats?gradeId=${selectedGrade.id}&quantity=${quantity}`
      : "";

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
    <div className="flex flex-col gap-5 rounded-xl border p-4">
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
                    {soldOut
                      ? "매진"
                      : `${grade.price.toLocaleString()}원 · 잔여 ${grade.remainingQuantity}석`}
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

      <StickyCta>
        <div className="flex items-center justify-between">
          <span className="text-sm text-muted-foreground">합계</span>
          <span className="text-lg font-bold">{totalPrice.toLocaleString()}원</span>
        </div>
        {canBook ? (
          <Link href={seatsHref} className={cn(buttonVariants({ size: "lg" }), "w-full")}>
            좌석 선택
          </Link>
        ) : (
          // 비활성 링크는 만들 수 없어서 버튼으로 둔다. 회차·등급을 고르면 Link로 바뀐다.
          <Button type="button" size="lg" className="w-full" disabled>
            좌석 선택
          </Button>
        )}
      </StickyCta>
    </div>
  );
}
