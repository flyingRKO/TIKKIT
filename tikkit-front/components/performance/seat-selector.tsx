"use client";

import { useActionState, useMemo, useState } from "react";
import { FormError } from "@/components/layout/form-error";
import { StickyCta } from "@/components/layout/sticky-cta";
import { GRADE_FILL, SeatMap } from "@/components/performance/seat-map";
import { Button } from "@/components/ui/button";
import { createReservationAction } from "@/lib/actions/reservation";
import { initialReservationActionState } from "@/lib/actions/reservation-state";
import { formatSeatLabel, GRADE_LABELS } from "@/lib/performance-labels";
import { toGradeMap, type SeatMapLayout } from "@/lib/seat-map";
import { cn, focusRing } from "@/lib/utils";
import type { ScheduleSeatResponse, TicketGradeResponse } from "@/types/api";

/** SVG 좌표 1단위를 몇 px로 그릴지. 전체 보기 → 확대 3단계. */
const ZOOM_LEVELS = [
  { label: "전체", scale: 1 },
  { label: "보통", scale: 2 },
  { label: "확대", scale: 3 },
] as const;

/** 기본값은 "보통". 전체 보기는 돔 기준 좌석이 8px이라 손가락으로 못 누른다. */
const DEFAULT_ZOOM = 1;

interface SeatSelectorProps {
  performanceId: number;
  performanceTitle: string;
  scheduleId: number;
  showAt: string;
  seats: ScheduleSeatResponse[];
  layout: SeatMapLayout;
  grades: TicketGradeResponse[];
  selectedGrade: TicketGradeResponse;
  quantity: number;
  /** 판매 기간 안인지. 화면에서 미리 막아줄 뿐이고 최종 판단은 BE가 한다. */
  bookable: boolean;
}

export function SeatSelector({
  performanceId,
  performanceTitle,
  scheduleId,
  showAt,
  seats,
  layout,
  grades,
  selectedGrade,
  quantity,
  bookable,
}: SeatSelectorProps) {
  const [selectedSeatIds, setSelectedSeatIds] = useState<number[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [zoomIndex, setZoomIndex] = useState(DEFAULT_ZOOM);
  const [state, formAction, pending] = useActionState(
    createReservationAction,
    initialReservationActionState
  );

  const gradeMap = useMemo(() => toGradeMap(grades), [grades]);

  // 좌석 수천 개를 고를 수 있는 것과 없는 것으로 한 번만 쪼갠다. 선택 상태와 무관하므로
  // 클릭마다 다시 계산되지 않아야 한다.
  const { selectableSeats, staticSeats } = useMemo(() => {
    const selectable: ScheduleSeatResponse[] = [];
    const rest: ScheduleSeatResponse[] = [];
    for (const seat of seats) {
      if (seat.ticketGradeId === selectedGrade.id && seat.status === "AVAILABLE") {
        selectable.push(seat);
      } else {
        rest.push(seat);
      }
    }
    return { selectableSeats: selectable, staticSeats: rest };
  }, [seats, selectedGrade.id]);

  const seatById = useMemo(
    () => new Map(selectableSeats.map((seat) => [seat.id, seat])),
    [selectableSeats]
  );

  // 선점에 실패해 서버가 배치도를 다시 내려줬다. 고른 좌석이 화면에 남아 있으면
  // 이미 사라진 자리를 고른 채로 또 누르게 된다.
  //
  // useEffect가 아니라 렌더 중에 바로 비운다. 서버 응답에서 파생되는 값이라
  // 화면을 한 번 그렸다가 되돌리면 낡은 선택이 한 프레임 보인다
  // (React "Adjusting state when a prop changes" 패턴).
  const [handledResetAt, setHandledResetAt] = useState<number | undefined>(undefined);
  if (state.resetSeatsAt !== undefined && state.resetSeatsAt !== handledResetAt) {
    setHandledResetAt(state.resetSeatsAt);
    setSelectedSeatIds([]);
    setNotice(null);
  }

  /**
   * 매수가 찬 뒤 다른 좌석을 누르면 **무시하고 안내**한다.
   *
   * 가장 먼저 고른 좌석을 밀어내는 방식도 있는데, 예매에서는 위험하다 — 누른 적 없는 자리가
   * 조용히 풀리고, 사용자는 그걸 눈치채지 못한 채 결제로 넘어간다.
   */
  function handleToggleSeat(seatId: number) {
    if (selectedSeatIds.includes(seatId)) {
      setSelectedSeatIds((ids) => ids.filter((id) => id !== seatId));
      setNotice(null);
      return;
    }
    if (selectedSeatIds.length >= quantity) {
      setNotice(`${quantity}석까지 고를 수 있습니다. 바꾸려면 고른 좌석을 먼저 해제해주세요.`);
      return;
    }
    setSelectedSeatIds((ids) => [...ids, seatId]);
    setNotice(null);
  }

  const zoom = ZOOM_LEVELS[zoomIndex];
  const filled = selectedSeatIds.length === quantity;
  const totalPrice = selectedGrade.price * selectedSeatIds.length;

  return (
    <form action={formAction} className="flex flex-col gap-3">
      <input type="hidden" name="performanceId" value={performanceId} />
      <input type="hidden" name="performanceTitle" value={performanceTitle} />
      <input type="hidden" name="scheduleId" value={scheduleId} />
      <input type="hidden" name="showAt" value={showAt} />
      <input type="hidden" name="ticketGradeId" value={selectedGrade.id} />
      <input type="hidden" name="grade" value={selectedGrade.grade} />
      <input type="hidden" name="quantity" value={quantity} />
      {/* 좌석은 hidden input 여러 개로 보낸다. FormData가 다중 값을 지원하므로 쉼표 조립이 필요 없다 */}
      {selectedSeatIds.map((seatId) => (
        <input key={seatId} type="hidden" name="seatIds" value={seatId} />
      ))}

      <div className="flex flex-wrap items-center justify-between gap-3">
        {/* 범례: 지금 고르는 등급만 진하게 */}
        <ul className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs">
          {grades.map((grade) => (
            <li key={grade.id} className="flex items-center gap-1.5">
              <svg width="10" height="10" aria-hidden="true">
                <rect
                  width="10"
                  height="10"
                  rx="2"
                  className={cn(
                    GRADE_FILL[grade.grade],
                    grade.id !== selectedGrade.id && "opacity-25"
                  )}
                />
              </svg>
              <span
                className={grade.id === selectedGrade.id ? "font-semibold" : "text-muted-foreground"}
              >
                {GRADE_LABELS[grade.grade]}
              </span>
            </li>
          ))}
          <li className="flex items-center gap-1.5 text-muted-foreground">
            <svg width="10" height="10" aria-hidden="true">
              <rect width="10" height="10" rx="2" className="fill-muted-foreground/35" />
            </svg>
            <span>판매 완료</span>
          </li>
        </ul>

        <div className="flex items-center gap-1" role="group" aria-label="배치도 확대">
          {ZOOM_LEVELS.map((level, index) => (
            <button
              key={level.label}
              type="button"
              onClick={() => setZoomIndex(index)}
              aria-pressed={index === zoomIndex}
              className={cn(
                "rounded-md border px-2 py-1 text-xs transition-colors",
                focusRing,
                index === zoomIndex
                  ? "border-primary bg-primary/5"
                  : "border-border text-muted-foreground hover:text-foreground"
              )}
            >
              {level.label}
            </button>
          ))}
        </div>
      </div>

      {/* 세로로 수십 열이라 높이를 제한하지 않으면 페이지가 한없이 길어진다 */}
      <div className="max-h-[70vh] overflow-auto rounded-xl border bg-card p-3">
        <div style={{ width: layout.width * zoom.scale }}>
          <SeatMap
            selectableSeats={selectableSeats}
            staticSeats={staticSeats}
            layout={layout}
            gradeMap={gradeMap}
            selectedGrade={selectedGrade}
            selectedSeatIds={selectedSeatIds}
            onToggleSeat={handleToggleSeat}
          />
        </div>
      </div>

      {/* 선택 현황은 배치도 바로 아래에 둔다. 하단 고정 CTA에 다 넣으면 모바일 화면을 절반 먹는다 */}
      <section className="rounded-xl border p-4">
        <h2 className="text-sm font-semibold">
          선택한 좌석 {selectedSeatIds.length}/{quantity}
        </h2>
        {selectedSeatIds.length === 0 ? (
          <p className="mt-2 text-sm text-muted-foreground">
            배치도에서 {GRADE_LABELS[selectedGrade.grade]} 좌석 {quantity}석을 골라주세요.
          </p>
        ) : (
          <ul className="mt-2 flex flex-col gap-1">
            {selectedSeatIds.map((seatId) => {
              const seat = seatById.get(seatId);
              if (seat === undefined) return null;
              return (
                <li key={seatId} className="flex items-center justify-between gap-2 text-sm">
                  <span className="min-w-0 break-words">{formatSeatLabel(seat)}</span>
                  <span className="flex shrink-0 items-center gap-2">
                    <span className="text-muted-foreground">
                      {selectedGrade.price.toLocaleString()}원
                    </span>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => handleToggleSeat(seatId)}
                      aria-label={`${formatSeatLabel(seat)} 선택 해제`}
                    >
                      해제
                    </Button>
                  </span>
                </li>
              );
            })}
          </ul>
        )}
        {notice !== null && (
          <p role="status" className="mt-2 text-sm text-muted-foreground">
            {notice}
          </p>
        )}
      </section>

      <StickyCta>
        <FormError message={state.error} />
        <div className="flex items-center justify-between">
          <span className="text-sm text-muted-foreground">합계</span>
          <span className="text-lg font-bold">{totalPrice.toLocaleString()}원</span>
        </div>
        <Button
          type="submit"
          size="lg"
          className="w-full"
          disabled={!bookable || !filled || pending}
        >
          {pending ? "선점 중..." : filled ? "예매하기" : `${quantity}석을 골라주세요`}
        </Button>
      </StickyCta>
    </form>
  );
}
