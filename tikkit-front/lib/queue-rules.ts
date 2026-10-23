import { MAX_QUANTITY } from "@/lib/schedule-rules";

// 대기열 화면의 순수 규칙 (Task 032). 서버 컴포넌트와 클라이언트 컴포넌트가 같이 쓰므로
// "use server"도 "use client"도 붙이지 않는다 (schedule-rules.ts / reservation-rules.ts와 같다).

/**
 * 대기열 화면이 좌석 화면으로 넘어가는 데 필요한 값 묶음.
 *
 * 좌석 화면은 performanceId(경로) + gradeId·quantity(쿼리)를 요구하는데 /queue/[scheduleId]에는
 * 회차 id만 있다. 그래서 나머지를 쿼리로 받아 그대로 들고 다닌다. 목적지 URL 문자열을 통째로
 * 넘기지 않는 이유는 그러면 오픈 리다이렉트 표면이 새로 생기기 때문이다 — 네 값이 전부 양의
 * 정수라 검증이 완전하고, 경로는 서버가 고정 템플릿에 정수만 끼워 만든다.
 */
export interface QueueParams {
  scheduleId: number;
  performanceId: number;
  gradeId: number;
  quantity: number;
}

function toPositiveInt(value: string | string[] | undefined): number | null {
  const first = Array.isArray(value) ? value[0] : value;
  if (first === undefined || first.trim() === "") {
    return null;
  }
  const parsed = Number(first);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

/** 하나라도 어긋나면 null이다. 호출부가 공연 상세로 되돌린다 — 회차는 존재하므로 404가 아니다. */
export function parseQueueParams(
  scheduleIdParam: string,
  query: Record<string, string | string[] | undefined>
): QueueParams | null {
  const scheduleId = toPositiveInt(scheduleIdParam);
  const performanceId = toPositiveInt(query.performanceId);
  const gradeId = toPositiveInt(query.gradeId);
  const quantity = toPositiveInt(query.quantity);

  if (scheduleId === null || performanceId === null || gradeId === null || quantity === null) {
    return null;
  }
  if (quantity > MAX_QUANTITY) {
    return null;
  }
  return { scheduleId, performanceId, gradeId, quantity };
}

/**
 * 좌석 선택 화면 경로. 등급·매수가 쿼리에 있어야 그 화면이 성립한다.
 *
 * lib/actions/reservation.ts에 있던 toSeatsPath를 여기로 옮겼다 — 대기열도 같은 경로를 만들어야
 * 해서 템플릿이 두 벌이 되면 한쪽만 고치는 사고가 난다.
 */
export function buildSeatsPath({ performanceId, scheduleId, gradeId, quantity }: QueueParams): string {
  return `/performances/${performanceId}/schedules/${scheduleId}/seats?gradeId=${gradeId}&quantity=${quantity}`;
}

/** 대기열 화면 경로. 좌석 화면으로 돌아갈 값들을 그대로 싣는다. */
export function buildQueuePath({ performanceId, scheduleId, gradeId, quantity }: QueueParams): string {
  return `/queue/${scheduleId}?performanceId=${performanceId}&gradeId=${gradeId}&quantity=${quantity}`;
}

// 진행바 비율의 하한·상한.
// 하한: 막 줄 선 사람에게도 바가 보여야 "동작하는 화면"으로 읽힌다.
// 상한: 대기 중에 100%가 차면 "다 찼는데 왜 안 들어가지"가 된다. 1번은 문구로 따로 말한다.
export const MIN_PROGRESS = 0.05;
export const MAX_PROGRESS = 0.95;

/**
 * 진행바 비율. 분모가 totalWaiting이 아니라 **처음 받은 내 순번(anchor)** 이다.
 *
 * totalWaiting을 분모로 쓰면 뒤에 사람이 늘어날 때 바가 되감긴다. BE가 ETA의 단조 비증가를
 * 지키려고 관측 속도 대신 공칭 속도를 쓴 것과 같은 이유로, 분모도 흔들리지 않는 값이어야 한다.
 * position은 FIFO + ZADD NX(재진입으로 앞자리를 못 산다) 때문에 단조 비증가가 보장된다.
 */
export function toProgressRatio(anchor: number, position: number): number {
  if (anchor <= 1) {
    return MAX_PROGRESS;
  }
  const done = (anchor - position) / (anchor - 1);
  return Math.min(MAX_PROGRESS, Math.max(MIN_PROGRESS, done));
}

/**
 * 예상 대기시간 문구. 분 단위로 뭉갠다.
 *
 * 초 단위로 보여주려면 클라이언트에서 1초씩 깎아야 하는데, ETA는 매 폴링마다 재계산되는
 * 추정치라 0에 닿은 뒤 다음 응답이 양수를 주면 숫자가 올라가 보인다 — BE가 지킨 단조성을
 * 프론트가 깨뜨리는 셈이다. 애초에 낙관적인 추정치를 초 단위로 보여주는 것도 정직하지 않다.
 */
export function formatWaitSeconds(seconds: number): string {
  if (seconds <= 0) {
    return "곧 입장합니다";
  }
  if (seconds < 60) {
    return "1분 미만";
  }
  const totalMinutes = Math.ceil(seconds / 60);
  if (totalMinutes < 60) {
    return `약 ${totalMinutes}분`;
  }
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return minutes === 0 ? `약 ${hours}시간` : `약 ${hours}시간 ${minutes}분`;
}

// 폴링 통신이 실패했을 때만 쓰는 백오프. **정상 경로의 간격은 여기 없다** —
// 응답의 pollAfterSeconds를 그대로 쓴다(그게 Task 033이 값을 조절할 수 있는 조건이다).
export const POLL_RETRY_BASE_SECONDS = 2;
export const POLL_RETRY_MAX_SECONDS = 15;
// 연속 실패가 이만큼 쌓이면 사용자에게 연결 문제를 알린다. 폴링은 멈추지 않는다.
export const POLL_FAILURE_NOTICE_THRESHOLD = 3;

export function toRetrySeconds(failureCount: number): number {
  const backoff = POLL_RETRY_BASE_SECONDS * 2 ** Math.max(0, failureCount - 1);
  return Math.min(POLL_RETRY_MAX_SECONDS, backoff);
}
