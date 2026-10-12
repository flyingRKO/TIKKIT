// 시나리오 3: 예매 스파이크 (오픈런) — Task 027
//
// ramping-arrival-rate를 쓰는 게 이 시나리오의 핵심이다.
//
// ramping-vus는 closed model이다 — 각 VU가 응답을 받고 나서 다음 요청을 보내므로,
// 서버가 느려지면 들어오는 부하도 같이 줄어든다. 그러면 "서버가 느려질수록 편해지는"
// 현실에 없는 상황을 측정하게 된다.
// 오픈런은 서버가 느려져도 사람들이 계속 들어오는 상황이다(open model). 그래서 VU 수가
// 아니라 **도착률**을 끌어올린다. Task 033에서 대기열 유무를 비교할 때 이 차이가 결과를 바꾼다.
//
// arrival-rate executor는 페이싱을 스스로 통제하므로 sleep()을 쓰지 않는다.
//
// 실행:
//   docker compose -f docker-compose.prod.yml --profile load run --rm k6 \
//     run -o experimental-prometheus-rw --summary-export=/results/booking-spike.json \
//     /scripts/booking-spike.js
import { check } from 'k6';
import { apiGet, apiPost, data, holdSuccess } from './lib/api.js';
import { ensureLogin, ACCOUNT_COUNT } from './lib/accounts.js';
import { collectScheduleIds, groupAvailableByGrade } from './lib/catalog.js';
import {
  BASE_THRESHOLDS,
  DURATION,
  RAMP_DOWN,
  RAMP_UP,
  SYSTEM_TAGS,
  pick,
} from './lib/config.js';

// 초당 도착 수. 004_3에서 curl + xargs로 측정한 최대 처리량이 81.9 req/s였으므로
// 그 근처를 기본 피크로 둔다. 한 iteration이 요청 3~4개를 보내니 실제 RPS는 이보다 높다.
const PEAK_RATE = Number(__ENV.PEAK_RATE || 30);

// 결제까지 가는 비율. 나머지는 취소한다.
//
// 반드시 둘 중 하나로 PENDING을 해소해야 한다 — 그냥 두면 10분 홀드가 남아서
// uk_reservations_pending_member_grade에 걸려 다음 iteration이 409가 된다.
// (만료 배치는 60초 주기지만 10분이 지나야 치운다)
const PAY_RATIO = Number(__ENV.PAY_RATIO || 0.7);

// 좌석 선택 전략: 'random'(기본, 기준선용) | 'head'(경쟁 재현용). 아래 사용처 주석 참조.
const SEAT_STRATEGY = __ENV.SEAT_STRATEGY || 'random';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      // VU 풀 크기. 서버가 느려지면 한 iteration이 길어져 더 많은 VU가 필요하다.
      // maxVUs를 계정 수 이하로 둬야 계정이 겹치지 않는다 (lib/accounts.js 참조).
      preAllocatedVUs: 50,
      maxVUs: Math.min(300, ACCOUNT_COUNT),
      stages: [
        { duration: RAMP_UP, target: PEAK_RATE },
        { duration: DURATION, target: PEAK_RATE },
        { duration: RAMP_DOWN, target: 0 },
      ],
    },
  },
  thresholds: BASE_THRESHOLDS,
  // url 라벨을 빼서 Prometheus 카디널리티를 막는다 (lib/config.js 주석 참조)
  systemTags: SYSTEM_TAGS,
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { scheduleIds: collectScheduleIds() };
}

export default function (setupData) {
  // 1) 로그인 (VU당 1회, 이후 iteration은 쿠키를 재사용한다)
  if (!ensureLogin()) {
    return;
  }

  // 2) 좌석맵에서 빈 좌석을 찾는다
  const scheduleId = pick(setupData.scheduleIds);
  const seatRes = apiGet(`/schedules/${scheduleId}/seats`, 'seatmap');
  const seats = data(seatRes);
  if (!seats || seats.length === 0) {
    return;
  }

  const byGrade = groupAvailableByGrade(seats);
  const gradeIds = Object.keys(byGrade);
  if (gradeIds.length === 0) {
    // 전 좌석이 HELD/SOLD다. 재고 소진은 정상 종료 조건이므로 에러로 세지 않는다.
    return;
  }

  // 3) 선점 요청
  //
  // 같은 등급에서만 좌석을 고른다 — 한 예약은 한 등급이라는 정책이 DB 복합 FK로 강제된다.
  // quantity와 seatIds.length가 정확히 같아야 하고 최대 4석이다.
  const ticketGradeId = Number(pick(gradeIds));
  const pool = byGrade[ticketGradeId];
  const quantity = Math.min(1 + Math.floor(Math.random() * 4), pool.length);

  // 좌석을 고르는 방식. 기준선 측정과 경쟁 재현이 서로 다른 값을 요구한다.
  //
  // random(기본): 무작위 시작점에서 연속으로 집는다. 2,000석 풀에서 1~4석을 집으면
  //   VU끼리 겹칠 확률이 거의 0이라 **경쟁이 발생하지 않는다.** 처리량·지연 기준선을
  //   재는 데는 이게 맞다 — 경쟁 때문에 롤백된 요청이 수치에 섞이지 않는다.
  //
  // head: 전원이 앞좌석부터 집는다. 같은 회차를 보는 VU끼리 좌석이 정확히 겹쳐
  //   409 SOLD_OUT이 나오고, 조건부 UPDATE(Task 019~022)의 경쟁 경로를 실제로 태운다.
  //   SEAT_STRATEGY=head + SAMPLE_SCHEDULES=1 + 높은 PEAK_RATE로 쓴다.
  //
  // 기본값을 random으로 둔 이유: 이 Task의 산출물은 "경쟁 하에서의 수치"가 아니라
  // 기준선이다. 경쟁 정확성은 Task 018~022의 동시성 테스트가 이미 증명했다.
  const offset =
    SEAT_STRATEGY === 'head' ? 0 : Math.floor(Math.random() * (pool.length - quantity + 1));
  const seatIds = pool.slice(offset, offset + quantity);

  const created = apiPost(
    '/reservations',
    { scheduleId: scheduleId, ticketGradeId: ticketGradeId, quantity: quantity, seatIds: seatIds },
    'reservation_create'
  );

  // 201이면 성공, 409 SOLD_OUT이면 경쟁에서 밀린 것 — 둘 다 서버가 올바르게 동작한 결과다.
  holdSuccess.add(created.res.status === 201);
  check(created.res, {
    '선점: 201 또는 정상 거절': function (res) {
      return res.status === 201 || res.status === 409;
    },
  });

  const reservation = data(created);
  if (!reservation) {
    return;
  }

  // 4) 결제 또는 취소 — PENDING을 반드시 해소한다
  if (Math.random() < PAY_RATIO) {
    apiPost(`/reservations/${reservation.id}/payments`, { method: 'CARD' }, 'reservation_pay');
  } else {
    apiPost(`/reservations/${reservation.id}/cancel`, null, 'reservation_cancel');
  }
}
