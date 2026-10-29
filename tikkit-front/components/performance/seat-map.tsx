"use client";

import { memo, useCallback, useMemo, useRef, useState } from "react";
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

/** focus-visible:ring은 box-shadow라서 SVG에 안 먹는다. outline을 직접 지정한다. */
const SEAT_FOCUS_OUTLINE =
  "focus-visible:[outline:2px_solid_var(--ring)] focus-visible:[outline-offset:1px]";

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

/**
 * 좌석 하나를 그리는 데 필요한 값만 미리 뽑아 둔 것 (Task 035).
 *
 * 좌표 환산과 aria-label 문자열 조립을 좌석마다 매 렌더 하지 않으려고 만들었다.
 * 10,000석 공연장이면 선택 가능 좌석이 4,000개라서, 렌더마다 좌석당 한 번씩만 해도
 * `toLocaleString()`과 문자열 조립이 4,000회씩 돈다.
 */
interface SeatView {
  id: number;
  x: number;
  y: number;
  ariaLabel: string;
}

interface SelectableSeatProps {
  view: SeatView;
  selected: boolean;
  focused: boolean;
  /** 레이어가 미리 만들어 준 클래스 문자열. 좌석마다 cn()을 돌리지 않는다. */
  selectedClassName: string;
  unselectedClassName: string;
}

/**
 * 좌석 하나.
 *
 * memo로 끊는 이유: 선택이 바뀌면 부모가 다시 돌지만, 실제로 바뀐 좌석은 최대 2개
 * (해제된 것 + 선택된 것)다. memo가 없으면 나머지 수천 개도 render 함수를 다시 돌면서
 * className 병합·문자열 조립·DOM 재조정을 전부 반복한다.
 *
 * props에 원시값과 고정 문자열만 두는 것이 memo가 실제로 먹기 위한 조건이다. 핸들러를
 * 좌석마다 넘기지 않고 부모 <g>에 한 번만 다는 것(이벤트 위임)도 같은 이유다.
 */
const SelectableSeat = memo(function SelectableSeat({
  view,
  selected,
  focused,
  selectedClassName,
  unselectedClassName,
}: SelectableSeatProps) {
  return (
    <rect
      x={view.x}
      y={view.y}
      width={SEAT_SIZE}
      height={SEAT_SIZE}
      rx={SEAT_RADIUS}
      role="checkbox"
      aria-checked={selected}
      aria-label={view.ariaLabel}
      // roving tabindex: 탭 스톱은 하나만 두고 좌석 사이는 화살표로 옮긴다.
      tabIndex={focused ? 0 : -1}
      className={selected ? selectedClassName : unselectedClassName}
      strokeWidth={selected ? 1.5 : 0}
    />
  );
});

interface SelectableSeatLayerProps {
  seats: ScheduleSeatResponse[];
  layout: SeatMapLayout;
  selectedGrade: TicketGradeResponse;
  selectedSeatIds: number[];
  focusIndex: number;
  groupRef: React.RefObject<SVGGElement | null>;
  onSeatClick: (event: React.MouseEvent<SVGGElement>) => void;
  onSeatKeyDown: (event: React.KeyboardEvent<SVGGElement>) => void;
}

/**
 * 지금 고르는 등급의 예매 가능 좌석을 그린다.
 *
 * 클릭 비용이 여기 있다. 10,000석 공연장에서 클릭당 32.5ms였고(009_2 6.5절), 원인은
 * `selectedSeatIds.includes()`가 아니다 — 009 4.8절이 선택 깊이별 비용의 평탄함을 실측해서
 * 그걸 이미 배제했다(상한이 4매라 배열 길이가 최대 4다). 실제 비용은 클릭 한 번에 이 레이어의
 * 좌석 4,000개가 전부 다시 조립되는 것이었다.
 *
 * 그래서 좌석당 반복을 레이어당 1회로 내린다 — 좌표·aria-label·클래스 문자열을 전부
 * 아래 useMemo에서 한 번만 만들고, 좌석은 그걸 받아 쓰는 memo 컴포넌트가 된다.
 */
const SelectableSeatLayer = memo(function SelectableSeatLayer({
  seats,
  layout,
  selectedGrade,
  selectedSeatIds,
  focusIndex,
  groupRef,
  onSeatClick,
  onSeatKeyDown,
}: SelectableSeatLayerProps) {
  const { seatViews, selectedClassName, unselectedClassName } = useMemo(() => {
    // 선택 가능 좌석은 전부 같은 등급이라 가격 문자열이 하나뿐이다. Intl 호출을 1회로 줄인다.
    const priceLabel = selectedGrade.price.toLocaleString();
    const selectedFill = GRADE_FILL[selectedGrade.grade];

    return {
      seatViews: seats.map((seat) => ({
        id: seat.id,
        x: seatX(seat, layout),
        y: seatY(seat, layout),
        ariaLabel: `${formatSeatLabel(seat)}, ${priceLabel}원`,
      })),
      // 선택/비선택 두 가지뿐이라 cn()도 2회로 줄어든다. fill은 선택 여부와 무관하고
      // 선택 표시는 테두리(stroke)다.
      selectedClassName: cn("cursor-pointer", selectedFill, "stroke-foreground", SEAT_FOCUS_OUTLINE),
      unselectedClassName: cn(
        "cursor-pointer",
        selectedFill,
        "hover:opacity-70",
        SEAT_FOCUS_OUTLINE
      ),
    };
  }, [seats, layout, selectedGrade]);

  // 상한이 4매라 Set으로 바꿔도 조회 비용은 거의 같다. 의도를 드러내려고 쓴다 —
  // 009 4.8절이 "이 규모에서 이득 없음"으로 기각한 전환이고, 그 판단은 여전히 맞다.
  const selectedIds = useMemo(() => new Set(selectedSeatIds), [selectedSeatIds]);

  return (
    // 좌석 인덱스를 형제 중 위치로 찾으므로, 이 그룹의 자식은 좌석 rect뿐이어야 한다.
    <g ref={groupRef} data-layer="selectable" onClick={onSeatClick} onKeyDown={onSeatKeyDown}>
      {seatViews.map((view, index) => (
        <SelectableSeat
          key={view.id}
          view={view}
          selected={selectedIds.has(view.id)}
          focused={index === focusIndex}
          selectedClassName={selectedClassName}
          unselectedClassName={unselectedClassName}
        />
      ))}
    </g>
  );
});

/**
 * 위임된 이벤트에서 좌석 인덱스를 되찾는다. 좌석 밖(통로·배경)에서 온 이벤트면 -1.
 *
 * ★ `data-seat-index`를 좌석마다 박지 않은 이유는 바이트다 ★
 *   속성 하나가 좌석당 약 23B라서 10,000석 공연장 문서가 약 92KB(+2.9%) 커진다.
 *   009_1·009_2가 계속 추적해 온 지표를 2.9% 악화시키면서 얻는 것은 클릭당 µs 단위다.
 *
 * 대신 형제 중 위치로 찾는다. 네이티브 포인터 비교라 4,000개를 훑어도 µs 수준이지만
 * **엄밀히는 클릭당 O(n) 스캔이 하나 남는다** — 그래서 "완전한 상수 시간"이라고 적지 않는다.
 * 재판단 조건: 2,000석과 10,000석의 클릭 비용 비가 1에 가까워지지 않으면 이 스캔이 원인인지
 * 확인하고, 그때 `data-seat-index`와 바이트를 맞바꾼다.
 */
function seatIndexFromEvent(event: React.SyntheticEvent<SVGGElement>): number {
  const group = event.currentTarget;
  const target = event.target;
  // 좌석이 아닌 곳에서 온 이벤트, 또는 좌석 안에 자식 요소가 생긴 경우를 막는다
  if (!(target instanceof Element) || target.parentNode !== group) {
    return -1;
  }
  return Array.prototype.indexOf.call(group.children, target);
}

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

  // 좌석마다 ref 콜백을 달지 않는다 (Task 035). 인라인 ref 콜백은 매 렌더 새 참조라서
  // React가 좌석 4,000개의 ref를 전부 떼고 다시 붙였다. 그룹 하나만 잡고 포커스 대상은
  // data 속성으로 찾는다.
  const groupRef = useRef<SVGGElement | null>(null);

  /** 인덱스로 좌석 요소를 찾는다. 그룹의 자식이 좌석 rect뿐이라는 전제에 기댄다. */
  const seatElementAt = useCallback((index: number): SVGElement | null => {
    const element = groupRef.current?.children.item(index) ?? null;
    return element instanceof SVGElement ? element : null;
  }, []);

  const focusSeat = useCallback(
    (index: number) => {
      const clamped = Math.max(0, Math.min(selectableSeats.length - 1, index));
      setFocusIndex(clamped);
      // tabIndex가 -1이어도 프로그램에서 부르는 focus()는 먹는다.
      seatElementAt(clamped)?.focus();
    },
    [selectableSeats.length, seatElementAt]
  );

  /**
   * 위/아래 줄에서 가로로 가장 가까운 좌석을 찾는다.
   *
   * 좌석 배열이 (posY, posX) 순이라 줄 경계는 posY가 바뀌는 지점이다. 선택 가능한 좌석만
   * 모아둔 배열이라 줄마다 좌석이 듬성듬성할 수 있어서, 같은 posX가 없으면 가장 가까운 칸으로 간다.
   */
  const findInAdjacentRow = useCallback(
    (index: number, direction: 1 | -1): number => {
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
    },
    [selectableSeats]
  );

  /**
   * 좌석 클릭. 핸들러를 좌석마다 달지 않고 그룹 하나에만 단다 (이벤트 위임, Task 035).
   *
   * 전에는 좌석 4,000개에 각각 onClick·onKeyDown 화살표 함수가 붙어서, 리스너가
   * 10,000석에서 4,335개였고(009_2) 매 렌더 함수가 새로 만들어져 memo가 먹지 않았다.
   */
  const handleSeatClick = useCallback(
    (event: React.MouseEvent<SVGGElement>) => {
      const index = seatIndexFromEvent(event);
      if (index < 0) {
        return;
      }
      setFocusIndex(index);
      onToggleSeat(selectableSeats[index].id);
    },
    [selectableSeats, onToggleSeat]
  );

  const handleSeatKeyDown = useCallback(
    (event: React.KeyboardEvent<SVGGElement>) => {
      const index = seatIndexFromEvent(event);
      if (index < 0) {
        return;
      }
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
    },
    [selectableSeats, onToggleSeat, focusSeat, findInAdjacentRow]
  );

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

      <SelectableSeatLayer
        seats={selectableSeats}
        layout={layout}
        selectedGrade={selectedGrade}
        selectedSeatIds={selectedSeatIds}
        focusIndex={focusIndex}
        groupRef={groupRef}
        onSeatClick={handleSeatClick}
        onSeatKeyDown={handleSeatKeyDown}
      />
    </svg>
  );
}
