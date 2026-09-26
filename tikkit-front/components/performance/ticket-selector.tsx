"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/lib/format-date";
import { GRADE_LABELS } from "@/lib/performance-labels";
import { cn } from "cn";
import type { ScheduleSummaryResponse, TicketGradeResponse } from "@/types/api";

const MAX_QUANTITY = 4;

interface TicketSelectorProps {
  schedules: ScheduleSummaryResponse[];
  ticketGradesBySchedule: Record<number, TicketGradeResponse[]>;
}

export function TicketSelector({ schedules, ticketGradesBySchedule }: TicketSelectorProps) {
  const [scheduleId, setScheduleId] = useState<number | null>(schedules[0]?.id ?? null);
  const [gradeId, setGradeId] = useState<number | null>(null);
  const [quantity, setQuantity] = useState(1);

  const grades = scheduleId ? ticketGradesBySchedule[scheduleId] ?? [] : [];
  const selectedGrade = grades.find((grade) => grade.id === gradeId) ?? null;
  const totalPrice = useMemo(
    () => (selectedGrade ? selectedGrade.price * quantity : 0),
    [selectedGrade, quantity]
  );

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
        <h3 className="text-sm font-semibold">회차 선택</h3>
        <div className="flex flex-col gap-1.5">
          {schedules.map((schedule) => (
            <button
              key={schedule.id}
              type="button"
              onClick={() => handleSelectSchedule(schedule.id)}
              className={cn(
                "rounded-md border px-3 py-2 text-left text-sm transition-colors",
                scheduleId === schedule.id
                  ? "border-primary bg-primary/5 text-foreground"
                  : "border-border text-muted-foreground hover:text-foreground"
              )}
              aria-pressed={scheduleId === schedule.id}
            >
              {formatDateTime(schedule.showAt)}
            </button>
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-2">
        <h3 className="text-sm font-semibold">등급 선택</h3>
        {grades.length === 0 ? (
          <p className="text-sm text-muted-foreground">등급 정보를 불러오지 못했습니다.</p>
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
          <h3 className="text-sm font-semibold">수량</h3>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={() => setQuantity((q) => Math.max(1, q - 1))}
              disabled={quantity <= 1}
              aria-label="수량 감소"
            >
              -
            </Button>
            <span className="w-6 text-center text-sm">{quantity}</span>
            <Button
              variant="outline"
              size="icon-sm"
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

      <div className="flex items-center justify-between border-t pt-4">
        <span className="text-sm text-muted-foreground">합계</span>
        <span className="text-lg font-bold">{totalPrice.toLocaleString()}원</span>
      </div>

      {/* TODO(Task 012): POST /reservations 연동 전이라 예매 기능은 아직 비활성화한다 */}
      <Button disabled={!selectedGrade} className="w-full" size="lg">
        예매하기 (준비 중)
      </Button>
    </div>
  );
}
