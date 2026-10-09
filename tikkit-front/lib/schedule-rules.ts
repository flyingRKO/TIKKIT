import type { ScheduleSummaryResponse } from "@/types/api";

/** 한 예약에 담을 수 있는 최대 매수. BE의 @Size(max = 4)와 같은 값이다. */
export const MAX_QUANTITY = 4;

export type SaleState = "BEFORE_OPEN" | "OPEN" | "CLOSED";

/**
 * 회차의 판매 기간 상태. 화면에서 미리 막아주는 용도일 뿐이고, 최종 판단은 BE(BOOKING_NOT_OPEN)가 한다.
 *
 * `now`를 인자로 받는 이유: 클라이언트에서는 렌더마다 Date.now()가 바뀌면 안 되고,
 * 서버에서는 요청 시각을 쓰면 되므로 호출부가 기준 시각을 정하게 둔다.
 */
export function getSaleState(schedule: ScheduleSummaryResponse, now: number): SaleState {
  if (now < new Date(schedule.bookingOpenAt).getTime()) return "BEFORE_OPEN";
  if (now >= new Date(schedule.bookingCloseAt).getTime()) return "CLOSED";
  return "OPEN";
}

/**
 * 요청 시각 기준 판매 상태. Server Component에서 쓴다.
 *
 * `Date.now()`를 여기서 부르는 이유: React Compiler의 purity 규칙(react-hooks/purity)이
 * 컴포넌트 본문에서의 비순수 호출을 막는다. 서버 렌더는 요청마다 한 번이라 시각을 읽는 게 맞는데,
 * 린트는 서버/클라이언트를 구분하지 못하므로 호출을 컴포넌트 밖으로 빼낸다.
 */
export function getSaleStateNow(schedule: ScheduleSummaryResponse): SaleState {
  return getSaleState(schedule, Date.now());
}
