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

/** 둥근 모서리의 직선 구간 길이. 양쪽 모서리 반지름을 뺀 값이다. */
const SEAT_STRAIGHT = SEAT_SIZE - SEAT_RADIUS * 2;

/**
 * 좌석 하나의 path 명령 중 좌표를 뺀 나머지. 좌석 크기가 전부 같아서 상수로 뽑힌다.
 *
 * 원호(a) 대신 2차 베지어(q)를 쓴다. 반지름 1.5에서 사분원과의 최대 오차가 0.1 단위 미만이라
 * 눈에 안 보이고, 명령 길이는 a 쪽이 좌석당 30바이트쯤 더 길다.
 */
const SEAT_SUBPATH_TAIL =
  `h${SEAT_STRAIGHT}` +
  `q${SEAT_RADIUS} 0 ${SEAT_RADIUS} ${SEAT_RADIUS}` +
  `v${SEAT_STRAIGHT}` +
  `q0 ${SEAT_RADIUS} ${-SEAT_RADIUS} ${SEAT_RADIUS}` +
  `h${-SEAT_STRAIGHT}` +
  `q${-SEAT_RADIUS} 0 ${-SEAT_RADIUS} ${-SEAT_RADIUS}` +
  `v${-SEAT_STRAIGHT}` +
  `q0 ${-SEAT_RADIUS} ${SEAT_RADIUS} ${-SEAT_RADIUS}` +
  "z";

interface StaticSeatLayerProps {
  seats: ScheduleSeatResponse[];
  layout: SeatMapLayout;
  gradeMap: Map<number, TicketGradeResponse>;
}

/**
 * 고를 수 없는 좌석(다른 등급 + 이미 팔린 좌석)을 그린다.
 *
 * 좌석마다 <rect>를 만들지 않고 "같은 색으로 칠할 좌석"끼리 하나의 <path>로 합친다
 * (Task 030_2). 색 조합이 등급 수 + 팔린 좌석 한 종류뿐이라 path는 최대 5개다.
 *
 * ★ 이건 전송량 레버가 아니다 ★
 *   rect 하나의 마크업과 좌석 하나의 path 명령이 둘 다 80바이트 안쪽이라 바이트는 거의
 *   그대로다. 줄어드는 것은 DOM 노드 수다 — SVG 요소 하나하나가 브라우저 메모리의 실객체라서
 *   10,000석 공연장이면 선택 불가 좌석 6,000개가 노드 6,000개였고, 합치면 4개가 된다.
 *   ROADMAP이 Task 030_2의 근거를 "전송량이 아니라 확장성"으로 적은 지점이 여기다.
 *
 * 선택 상태와 무관한 레이어라 memo는 그대로 둔다. 다만 memo가 막아주는 것은 "클릭할 때
 * 다시 그리는 비용"이고, 이 변경이 줄이는 것은 "처음 만드는 노드 수와 그 메모리"다.
 * 둘은 다른 비용이라 한쪽이 있다고 다른 쪽이 해결되지 않는다.
 *
 * 좌석끼리 겹치지 않으므로 그리는 순서를 색깔별로 묶어도 겉모습은 같다.
 */
const StaticSeatLayer = memo(function StaticSeatLayer({
  seats,
  layout,
  gradeMap,
}: StaticSeatLayerProps) {
  const groups = new Map<string, { className: string; commands: string[] }>();

  for (const seat of seats) {
    const grade = gradeMap.get(seat.ticketGradeId);
    const sold = seat.status !== "AVAILABLE";
    // 팔린 좌석은 등급과 무관하게 한 덩어리다. 등급을 못 찾은 좌석(gradeMap에 없음)은
    // 원래도 fill 없이 흐리게만 그려졌으므로 그 상태 그대로 따로 묶는다.
    const key = sold ? "sold" : (grade?.grade ?? "unknown");

    let group = groups.get(key);
    if (group === undefined) {
      group = {
        // 팔린 좌석은 등급 색을 지우고 회색으로, 다른 등급은 색은 두고 흐리게 —
        // "살 수 없음"과 "지금 고르는 등급이 아님"을 다른 축으로 구분한다.
        className: cn(
          sold ? "fill-muted-foreground/35" : grade && GRADE_FILL[grade.grade],
          !sold && "opacity-25"
        ),
        commands: [],
      };
      groups.set(key, group);
    }

    group.commands.push(
      `M${seatX(seat, layout) + SEAT_RADIUS} ${seatY(seat, layout)}${SEAT_SUBPATH_TAIL}`
    );
  }

  return (
    <g aria-hidden="true">
      {[...groups].map(([key, group]) => (
        <path key={key} d={group.commands.join("")} className={group.className} />
      ))}
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
