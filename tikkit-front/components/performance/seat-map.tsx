"use client";

import { memo, useRef, useState } from "react";
import { formatSeatLabel } from "@/lib/performance-labels";
import { SEAT_SIZE, seatX, seatY, type SeatMapLayout } from "@/lib/seat-map";
import { cn } from "@/lib/utils";
import type { Grade, ScheduleSeatResponse, TicketGradeResponse } from "@/types/api";

/** globals.css의 --grade-* 토큰. Tailwind v4의 @theme 매핑으로 fill 유틸리티가 바로 생긴다. */
export const GRADE_FILL: Record<Grade, string> = {
  VIP: "fill-grade-vip",
  R: "fill-grade-r",
  S: "fill-grade-s",
  A: "fill-grade-a",
};

const SEAT_RADIUS = 1.5;

interface StaticSeatLayerProps {
  seats: ScheduleSeatResponse[];
  layout: SeatMapLayout;
  gradeMap: Map<number, TicketGradeResponse>;
}

/**
 * 고를 수 없는 좌석(다른 등급 + 이미 팔린 좌석)을 그린다.
 *
 * 선택 상태와 무관한 레이어라 memo로 끊어낸다. 3,000석 공연장에서 좌석 하나를 누를 때마다
 * 2,850개 rect를 다시 만들면 클릭이 눈에 보이게 밀린다. React Compiler가 꺼져 있어
 * (next.config.ts에 설정 없음) 자동 메모이제이션은 기대할 수 없다.
 */
const StaticSeatLayer = memo(function StaticSeatLayer({
  seats,
  layout,
  gradeMap,
}: StaticSeatLayerProps) {
  return (
    <g aria-hidden="true">
      {seats.map((seat) => {
        const grade = gradeMap.get(seat.ticketGradeId);
        const sold = seat.status !== "AVAILABLE";
        return (
          <rect
            key={seat.id}
            x={seatX(seat, layout)}
            y={seatY(seat, layout)}
            width={SEAT_SIZE}
            height={SEAT_SIZE}
            rx={SEAT_RADIUS}
            // 팔린 좌석은 등급 색을 지우고 회색으로, 다른 등급은 색은 두고 흐리게 —
            // "살 수 없음"과 "지금 고르는 등급이 아님"을 다른 축으로 구분한다.
            className={cn(
              sold ? "fill-muted-foreground/35" : grade && GRADE_FILL[grade.grade],
              !sold && "opacity-25"
            )}
          />
        );
      })}
    </g>
  );
});

interface SeatMapProps {
  /** 지금 고르는 등급의 예매 가능 좌석. 배치도 순서(앞열 → 왼쪽)로 정렬돼 있다. */
  selectableSeats: ScheduleSeatResponse[];
  /** 고를 수 없는 좌석 전부 */
  staticSeats: ScheduleSeatResponse[];
  layout: SeatMapLayout;
  gradeMap: Map<number, TicketGradeResponse>;
  selectedGrade: TicketGradeResponse;
  selectedSeatIds: number[];
  onToggleSeat: (seatId: number) => void;
}

export function SeatMap({
  selectableSeats,
  staticSeats,
  layout,
  gradeMap,
  selectedGrade,
  selectedSeatIds,
  onToggleSeat,
}: SeatMapProps) {
  // roving tabindex: 좌석 전체가 탭 스톱이 되면 키보드 사용자가 요약 패널까지 가는 데
  // 수백 번을 눌러야 한다. 탭 스톱은 하나만 두고 좌석 사이는 화살표로 옮긴다.
  const [focusIndex, setFocusIndex] = useState(0);
  const seatRefs = useRef<(SVGRectElement | null)[]>([]);

  const selectedFill = GRADE_FILL[selectedGrade.grade];

  function focusSeat(index: number) {
    const clamped = Math.max(0, Math.min(selectableSeats.length - 1, index));
    setFocusIndex(clamped);
    seatRefs.current[clamped]?.focus();
  }

  /**
   * 위/아래 줄에서 가로로 가장 가까운 좌석을 찾는다.
   *
   * 좌석 배열이 (posY, posX) 순이라 줄 경계는 posY가 바뀌는 지점이다. 선택 가능한 좌석만
   * 모아둔 배열이라 줄마다 좌석이 듬성듬성할 수 있어서, 같은 posX가 없으면 가장 가까운 칸으로 간다.
   */
  function findInAdjacentRow(index: number, direction: 1 | -1): number {
    const current = selectableSeats[index];
    let cursor = index;
    while (
      cursor >= 0 &&
      cursor < selectableSeats.length &&
      selectableSeats[cursor].posY === current.posY
    ) {
      cursor += direction;
    }
    if (cursor < 0 || cursor >= selectableSeats.length) return index;

    const targetRow = selectableSeats[cursor].posY;
    let best = cursor;
    let bestDistance = Math.abs(selectableSeats[cursor].posX - current.posX);
    for (
      let i = cursor;
      i >= 0 && i < selectableSeats.length && selectableSeats[i].posY === targetRow;
      i += direction
    ) {
      const distance = Math.abs(selectableSeats[i].posX - current.posX);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = i;
      }
    }
    return best;
  }

  function handleKeyDown(event: React.KeyboardEvent<SVGRectElement>, index: number) {
    switch (event.key) {
      case "Enter":
      case " ":
        // Space를 막지 않으면 스크롤 컨테이너가 내려간다
        event.preventDefault();
        onToggleSeat(selectableSeats[index].id);
        break;
      case "ArrowRight":
        event.preventDefault();
        focusSeat(index + 1);
        break;
      case "ArrowLeft":
        event.preventDefault();
        focusSeat(index - 1);
        break;
      case "ArrowDown":
        event.preventDefault();
        focusSeat(findInAdjacentRow(index, 1));
        break;
      case "ArrowUp":
        event.preventDefault();
        focusSeat(findInAdjacentRow(index, -1));
        break;
      case "Home":
        event.preventDefault();
        focusSeat(0);
        break;
      case "End":
        event.preventDefault();
        focusSeat(selectableSeats.length - 1);
        break;
      default:
        break;
    }
  }

  return (
    <svg
      viewBox={`0 0 ${layout.width} ${layout.height}`}
      // 폭은 바깥 컨테이너가 정한다(확대 배율). viewBox가 있으니 높이는 종횡비로 따라온다.
      width="100%"
      role="group"
      aria-label="좌석 배치도. 화살표 키로 좌석을 옮기고 Enter로 선택합니다."
      className="block h-auto max-w-none"
    >
      {/* 무대 */}
      <rect
        x={layout.stage.x}
        y={layout.stage.y}
        width={layout.stage.width}
        height={layout.stage.height}
        rx={2}
        className="fill-muted"
      />
      <text
        x={layout.stage.x + layout.stage.width / 2}
        y={layout.stage.y + layout.stage.height / 2}
        textAnchor="middle"
        dominantBaseline="central"
        className="fill-muted-foreground text-[8px] font-semibold"
      >
        STAGE
      </text>

      {/* 구역 이름 */}
      {layout.sections.map((section) => (
        <text
          key={section.section}
          x={section.x}
          y={section.y}
          textAnchor="middle"
          className="fill-muted-foreground text-[6px]"
        >
          {section.section}
        </text>
      ))}

      <StaticSeatLayer seats={staticSeats} layout={layout} gradeMap={gradeMap} />

      {selectableSeats.map((seat, index) => {
        const selected = selectedSeatIds.includes(seat.id);
        return (
          <rect
            key={seat.id}
            ref={(element) => {
              seatRefs.current[index] = element;
            }}
            x={seatX(seat, layout)}
            y={seatY(seat, layout)}
            width={SEAT_SIZE}
            height={SEAT_SIZE}
            rx={SEAT_RADIUS}
            role="checkbox"
            aria-checked={selected}
            aria-label={`${formatSeatLabel(seat)}, ${selectedGrade.price.toLocaleString()}원`}
            tabIndex={index === focusIndex ? 0 : -1}
            onClick={() => {
              setFocusIndex(index);
              onToggleSeat(seat.id);
            }}
            onKeyDown={(event) => handleKeyDown(event, index)}
            // focus-visible:ring은 box-shadow라서 SVG에 안 먹는다. outline을 직접 지정한다.
            className={cn(
              "cursor-pointer",
              selectedFill,
              selected ? "stroke-foreground" : "hover:opacity-70",
              "focus-visible:[outline:2px_solid_var(--ring)] focus-visible:[outline-offset:1px]"
            )}
            strokeWidth={selected ? 1.5 : 0}
          />
        );
      })}
    </svg>
  );
}
