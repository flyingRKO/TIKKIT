"use client";

import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef } from "react";
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

/**
 * 좌석 하나의 클래스. **선택 여부가 여기 안 들어간다** (Task 035).
 *
 * 전에는 `selected ? "stroke-foreground" : "hover:opacity-70"` 삼항이 있었다. 선택 표시를
 * 아래 `SEAT_LAYER_CLASS`로 옮기면서 사라졌고, 남은 것은 선택과 무관한 것뿐이다.
 */
const SEAT_CLASS = cn(
  "cursor-pointer",
  "hover:opacity-70",
  // focus-visible:ring은 box-shadow라서 SVG에 안 먹는다. outline을 직접 지정한다.
  "focus-visible:[outline:2px_solid_var(--ring)] focus-visible:[outline-offset:1px]"
);

/**
 * 선택 표시 규칙. **좌석이 아니라 좌석을 담는 `<g>`에 한 번만 붙는다** (Task 035).
 *
 * `aria-checked`는 `role="checkbox"` 때문에 어차피 좌석에 있어야 하는 속성이다. 그 속성을
 * CSS 선택자로 쓰면 **속성 하나만 바꿔도 테두리가 따라오고**, 선택 상태를 React props로
 * 내려보낼 필요가 없어진다. 그게 이 Task의 핵심이다.
 *
 * ★ 왜 좌석마다 `aria-checked:...`를 붙이지 않는가 ★
 *   처음엔 좌석 클래스에 `aria-checked:stroke-foreground` 같은 유틸리티를 넣었는데,
 *   **10,000석 문서가 3,111 → 3,479KB(+11.8%)로 불었다.** Tailwind 유틸리티는 클래스
 *   문자열이 요소마다 HTML에 그대로 복사되므로, 좌석 4,000개에서는 "CSS 한 줄"이 아니라
 *   "HTML 367KB"다. 같은 이유로 이 파일은 `data-seat-index`(+2.9%)도 쓰지 않는다 —
 *   한쪽만 지키면 앞뒤가 안 맞는다.
 *
 *   자식 선택자 변이자로 올리면 이 문자열이 문서에 딱 한 번 들어간다.
 *
 * `hover:opacity-100`은 전에 삼항으로 하던 것을 대신한다 — 선택된 좌석은 hover로 흐려지지
 * 않아야 테두리가 또렷하게 보인다. 부모 클래스 + 속성 선택자라 좌석의 `hover:opacity-70`보다
 * 명시도가 높아 그대로 덮는다.
 *
 * 비선택 좌석에 `stroke-width: 0`을 주지 않는 이유: SVG 기본 `stroke`가 `none`이라 폭이
 * 얼마든 안 보인다. 전에 좌석마다 찍히던 `stroke-width="0"` 속성이 이 변경으로 사라진다.
 */
const SEAT_LAYER_CLASS = cn(
  "[&>rect[aria-checked=true]]:stroke-foreground",
  "[&>rect[aria-checked=true]]:[stroke-width:1.5]",
  "[&>rect[aria-checked=true]]:hover:opacity-100"
);

/**
 * 서버 렌더에서는 `useLayoutEffect`가 아무 일도 안 하고 경고만 찍는다.
 * 브라우저에서만 layout effect를 쓴다 — 왜 layout effect여야 하는지는 `syncSelection` 주석 참조.
 */
const useIsomorphicLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

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
 */
interface SeatView {
  id: number;
  x: number;
  y: number;
  ariaLabel: string;
}

interface SelectableSeatLayerProps {
  seats: ScheduleSeatResponse[];
  layout: SeatMapLayout;
  selectedGrade: TicketGradeResponse;
  groupRef: React.RefObject<SVGGElement | null>;
  onSeatClick: (event: React.MouseEvent<SVGGElement>) => void;
  onSeatKeyDown: (event: React.KeyboardEvent<SVGGElement>) => void;
}

/**
 * 지금 고르는 등급의 예매 가능 좌석을 그린다.
 *
 * ★ props에 선택 상태도 포커스 위치도 없다. 그게 이 레이어의 전부다 ★
 *
 *   남은 props는 전부 세션 중에 안 바뀐다 — `seats`·`layout`·`selectedGrade`는 서버에서
 *   오거나 부모의 useMemo가 고정하고, 핸들러 둘은 useCallback으로 고정돼 있다. 줌도 SVG가
 *   아니라 바깥 div 폭이라(seat-selector.tsx) 이 레이어를 안 건드린다.
 *   **그래서 이 레이어는 마운트된 뒤 한 번도 다시 그려지지 않는다.**
 *
 *   그 대가로 좌석의 `aria-checked`와 `tabIndex`를 React가 아니라 SeatMap의 layout effect가
 *   쓴다. 아래 두 값이 **상수여야** 그 구조가 성립한다 — React는 DOM이 아니라 이전 렌더의
 *   props와 비교하므로, 상수면 다시 그려져도 그 속성에 손대지 않는다.
 *
 * 클릭당 비용이 좌석 수를 따라가던 원인이 여기 있었다. 10,000석에서 클릭 한 번에 좌석
 * 4,000개가 전부 다시 조립됐고(009_2 6.5절: 32.5ms), 원인은 `selectedSeatIds.includes()`가
 * 아니다 — 009 4.8절이 선택 깊이별 비용의 평탄함을 실측해서 그걸 이미 배제했다.
 */
const SelectableSeatLayer = memo(function SelectableSeatLayer({
  seats,
  layout,
  selectedGrade,
  groupRef,
  onSeatClick,
  onSeatKeyDown,
}: SelectableSeatLayerProps) {
  const { seatViews, className } = useMemo(() => {
    // 선택 가능 좌석은 전부 같은 등급이라 가격 문자열이 하나뿐이다. Intl 호출을 1회로 줄인다.
    const priceLabel = selectedGrade.price.toLocaleString();
    return {
      seatViews: seats.map((seat) => ({
        id: seat.id,
        x: seatX(seat, layout),
        y: seatY(seat, layout),
        ariaLabel: `${formatSeatLabel(seat)}, ${priceLabel}원`,
      })) satisfies SeatView[],
      // fill은 선택 여부와 무관하다. 선택 표시는 테두리이고 SEAT_CLASS의 CSS가 그린다.
      className: cn(SEAT_CLASS, GRADE_FILL[selectedGrade.grade]),
    };
  }, [seats, layout, selectedGrade]);

  return (
    // 좌석 인덱스를 형제 중 위치로 찾으므로, 이 그룹의 자식은 좌석 rect뿐이어야 한다.
    // 선택 표시 규칙도 그 전제(직계 자식 rect)에 기대고 있다.
    <g
      ref={groupRef}
      data-layer="selectable"
      className={SEAT_LAYER_CLASS}
      onClick={onSeatClick}
      onKeyDown={onSeatKeyDown}
    >
      {seatViews.map((view, index) => (
        <rect
          key={view.id}
          x={view.x}
          y={view.y}
          width={SEAT_SIZE}
          height={SEAT_SIZE}
          rx={SEAT_RADIUS}
          role="checkbox"
          aria-label={view.ariaLabel}
          className={className}
          // ★ 아래 둘은 상수다 ★ 바꾸는 주체는 SeatMap의 syncSelection / moveFocusAnchor
          //   하나뿐이다. 여기에 변수를 넣으면 레이어가 선택마다 다시 그려지고, 동시에
          //   두 주체가 같은 속성을 쓰게 된다.
          aria-checked={false}
          tabIndex={index === 0 ? 0 : -1}
        />
      ))}
    </g>
  );
});

/** 값이 다를 때만 쓴다. 측정 하네스가 MutationObserver로 이 속성을 보기 때문에 과한 신호를 막는다. */
function setSeatChecked(element: SVGElement | null, checked: boolean): void {
  const next = checked ? "true" : "false";
  if (element !== null && element.getAttribute("aria-checked") !== next) {
    element.setAttribute("aria-checked", next);
  }
}

function setSeatTabIndex(element: SVGElement | null, focusable: boolean): void {
  const next = focusable ? "0" : "-1";
  if (element !== null && element.getAttribute("tabindex") !== next) {
    element.setAttribute("tabindex", next);
  }
}

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
  const groupRef = useRef<SVGGElement | null>(null);

  // roving tabindex: 좌석 전체가 탭 스톱이 되면 키보드 사용자가 요약 패널까지 가는 데
  // 수백 번을 눌러야 한다. 탭 스톱은 하나만 두고 좌석 사이는 화살표로 옮긴다.
  //
  // state가 아니라 ref인 이유: 이 값이 state였을 때 화살표 이동과 클릭이 **선택과 별개로**
  // 레이어 전체를 다시 그리게 했다. 선택을 아무리 잘 끊어도 이쪽이 혼자서 좌석 4,000개를
  // 다시 돌렸다. 아무 렌더도 이 값을 읽지 않게 만들어서 클릭 경로에서 뺐다.
  const focusIndexRef = useRef(0);

  /** 마지막으로 DOM에 써 둔 선택 인덱스. "내가 쓴 것"의 유일한 기록이다. */
  const appliedSelectionRef = useRef<number[]>([]);
  /** 레이어가 새로 마운트됐는지 판별하려고 들고 있는 좌석 배열의 정체성. */
  const seatsRef = useRef(selectableSeats);

  /** 인덱스로 좌석 요소를 찾는다. 그룹의 자식이 좌석 rect뿐이라는 전제에 기댄다. */
  const seatElementAt = useCallback((index: number): SVGElement | null => {
    const element = groupRef.current?.children.item(index) ?? null;
    return element instanceof SVGElement ? element : null;
  }, []);

  const indexById = useMemo(
    () => new Map(selectableSeats.map((seat, index) => [seat.id, index])),
    [selectableSeats]
  );

  // 등급이 바뀌어 사라진 좌석 id는 여기서 걸러진다. 그래서 선택 표시가 유령으로 남지 않는다.
  const selectedIndices = useMemo(
    () =>
      selectedSeatIds
        .map((id) => indexById.get(id))
        .filter((index): index is number => index !== undefined),
    [selectedSeatIds, indexById]
  );

  /**
   * 선택 상태를 DOM에 반영한다. **`aria-checked`의 유일한 작성자다.**
   *
   * 왜 이벤트 핸들러가 아니라 layout effect인가:
   *   핸들러에서 바로 쓰면 측정 하네스(seat-interaction.spec.ts)가 보는 t1이 클릭 직후
   *   0.0x ms가 된다. 수치는 좋아지지만 그건 개선이 아니라 **자를 부러뜨린 것**이다 —
   *   t1의 정의가 "React 커밋"이기 때문이다. layout effect는 커밋 단계에서, DOM 변경 뒤·
   *   페인트 전에 돌아서 그 정의를 그대로 유지한다.
   *
   * 왜 deps 배열이 없는가:
   *   "레이어가 다시 마운트됐는지"를 추론하는 대신 매 렌더 확인한다. 만지는 요소가
   *   (이전 선택 ∪ 현재 선택 ∪ 포커스) 최대 9개뿐이라 O(n) 루프가 없다.
   */
  useIsomorphicLayoutEffect(() => {
    // 등급이 바뀌면 좌석 요소가 통째로 새로 마운트돼서 JSX 상수값으로 돌아간다.
    // 그때는 "내가 쓴 것"의 기록도 같이 버려야 한다.
    if (seatsRef.current !== selectableSeats) {
      seatsRef.current = selectableSeats;
      appliedSelectionRef.current = [];
      focusIndexRef.current = 0;
    }

    const next = new Set(selectedIndices);
    for (const index of appliedSelectionRef.current) {
      if (!next.has(index)) {
        setSeatChecked(seatElementAt(index), false);
      }
    }
    for (const index of selectedIndices) {
      setSeatChecked(seatElementAt(index), true);
    }
    appliedSelectionRef.current = selectedIndices;

    // 포커스 앵커도 같은 이유로 다시 씌운다(리마운트 뒤에는 0번 좌석이 앵커다).
    if (focusIndexRef.current !== 0) {
      setSeatTabIndex(seatElementAt(0), false);
      setSeatTabIndex(seatElementAt(focusIndexRef.current), true);
    }
  });

  /** 탭 스톱만 옮긴다. 포커스는 건드리지 않는다 — 클릭 경로가 쓰는 쪽이다. */
  const moveFocusAnchor = useCallback(
    (index: number): number => {
      const clamped = Math.max(0, Math.min(selectableSeats.length - 1, index));
      const previous = focusIndexRef.current;
      if (previous !== clamped) {
        setSeatTabIndex(seatElementAt(previous), false);
        setSeatTabIndex(seatElementAt(clamped), true);
        focusIndexRef.current = clamped;
      }
      return clamped;
    },
    [selectableSeats.length, seatElementAt]
  );

  /** 탭 스톱을 옮기고 실제로 포커스까지 준다 — 키보드 이동이 쓰는 쪽이다. */
  const focusSeat = useCallback(
    (index: number) => {
      // tabIndex가 -1이어도 프로그램에서 부르는 focus()는 먹는다.
      seatElementAt(moveFocusAnchor(index))?.focus();
    },
    [moveFocusAnchor, seatElementAt]
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
   * 10,000석에서 4,335개였고 매 렌더 함수가 새로 만들어져 memo가 먹지 않았다.
   */
  const handleSeatClick = useCallback(
    (event: React.MouseEvent<SVGGElement>) => {
      const index = seatIndexFromEvent(event);
      if (index < 0) {
        return;
      }
      moveFocusAnchor(index);
      onToggleSeat(selectableSeats[index].id);
    },
    [selectableSeats, onToggleSeat, moveFocusAnchor]
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
        groupRef={groupRef}
        onSeatClick={handleSeatClick}
        onSeatKeyDown={handleSeatKeyDown}
      />
    </svg>
  );
}
