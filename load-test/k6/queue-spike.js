// 시나리오 4: 대기열을 경유하는 오픈런 — Task 033
//
// booking-spike.js의 C군 짝이다. 같은 ramping-arrival-rate로 같은 도착률을 쏘고, 차이는
// "좌석 API를 직접 때리는가(off) vs 대기열을 거치는가(on)" 하나다. executor가 다르면
// 재는 것이 대기열 효과가 아니라 부하 모델 차이가 된다.
//
// ── 폴링을 VU 내부 while로 돌리지 않는다 ────────────────────────────────────
// 줄을 선 사람은 입장까지 수십 초~수 분을 폴링한다. 그걸 iteration 안에서 재현하면 한
// iteration이 그만큼 길어지고, arrival-rate executor는 VU 풀이 고갈되면 도착을 버린다
// (dropped_iterations). **그건 서버 거동이 아니라 부하 생성기의 한계라서, 그 상태의 p95는
// "서버가 버틴 결과"가 아니다.**
//
// 대신 대기열 멤버십이 Redis에 남는 성질을 쓴다 — 같은 계정이 다음 iteration에서 진입 API를
// 다시 부르면 ZADD NX라 순번이 유지된 채 "폴링하는 대기자"가 된다. 즉 폴링을 VU 루프가
// 아니라 **iteration 반복으로** 표현한다. 한 iteration은 짧게 끝나고 도착률이 보존된다.
//
// 실제 폴링 간격(2/5/10초)이 상태 API 용량에 미치는 영향은 이 스크립트가 아니라
// queue-poll.js가 전담한다.
//
// ── 반드시 SCHEDULE_IDS를 넘긴다 ─────────────────────────────────────────────
// 04-enable-queue.sql이 켠 회차만 때려야 한다. 안 넘기면 catalog.js가 ON_SALE 3,000회차에서
// 60개를 긁어오는데 대부분 queue_enabled=false라 DISABLED로 통과하고 군이 섞인다.
//
// 실행:
//   docker compose -f docker-compose.prod.yml --profile load run --rm k6 \
//     run -o experimental-prometheus-rw -e QUEUE_EXPECTED=1 -e SCHEDULE_IDS=<04-enable-queue.sql이 찍은 id들> \
//     -e PEAK_RATE=120 --summary-export=/results/t033-c-queue-spike-1.json \
//     /scripts/queue-spike.js
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { apiGet, apiPost, data, holdSuccess, realErrors } from './lib/api.js';
import { ensureLogin, ACCOUNT_COUNT } from './lib/accounts.js';
import { collectScheduleIds, groupAvailableByGrade, seatCount } from './lib/catalog.js';
import { SYSTEM_TAGS, arrivalStages, pick, thresholdsFor } from './lib/config.js';

const PEAK_RATE = Number(__ENV.PEAK_RATE || 30);
const PAY_RATIO = Number(__ENV.PAY_RATIO || 0.7);
const MAX_VUS = Number(__ENV.MAX_VUS || 300);

// 진입/폴링 응답에서 입장한 비율. 대기열의 핵심 지표다 —
// 이 값이 낮으면서 좌석 API p95가 멀쩡하면 대기열이 제 역할을 한 것이고,
// 이 값이 높으면 입장 속도(admit-batch-size x 켠 회차 수)가 아직 크다는 뜻이다.
const admitted = new Rate('tikkit_queue_admitted');

// 백엔드가 지시한 다음 폴링 간격. 어느 구간(2/5/10초)을 실제로 밟았는지가
// 결과 해석의 전제다. 032는 2초·5초만 밟고 10초 구간을 미검증으로 남겼다.
const pollInterval = new Trend('tikkit_queue_poll_after_seconds');

// 내 순번. 대기열이 실제로 깊어지는지 본다. 이게 1~2에 머물면 줄이 안 선 것이고
// 그러면 B군과 같은 것을 재고 있다.
const position = new Trend('tikkit_queue_position');

export const options = {
  scenarios: {
    queueSpike: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: Math.min(MAX_VUS, ACCOUNT_COUNT),
      stages: arrivalStages(PEAK_RATE),
    },
  },
  thresholds: thresholdsFor(),
  systemTags: SYSTEM_TAGS,
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const scheduleIds = collectScheduleIds();
  if (!__ENV.SCHEDULE_IDS) {
    // 던지지 않고 경고만 한다 — 스모크 실행에서 id를 모르는 채로 돌려보는 게 유용하다.
    console.warn(
      'SCHEDULE_IDS가 비어 있다. 대기열이 꺼진 회차가 섞이면 DISABLED가 올라오고 측정이 오염된다.'
    );
  }
  return { scheduleIds: scheduleIds };
}

export default function (setupData) {
  // 1) 로그인 (VU당 1회). 대기열은 memberId로 줄을 세우므로 세션이 없으면 전부 401이다.
  if (!ensureLogin()) {
    return;
  }

  const scheduleId = pick(setupData.scheduleIds);

  // 2) 진입. 멱등(ZADD NX)이라 iteration마다 불러도 순번이 밀리지 않는다.
  //    이미 줄 서 있으면 현재 순번이 오고, 이미 입장했으면 ADMITTED가 온다 —
  //    그래서 상태 조회를 따로 부르지 않아도 된다(요청 1회로 끝난다).
  const entered = apiPost(`/queue/schedules/${scheduleId}/entries`, null, 'queue_enter');
  const state = data(entered);
  if (!state) {
    return;
  }

  check(entered.res, {
    '진입: 대기열이 켜져 있다': function () {
      return state.state !== 'DISABLED';
    },
  });
  if (state.state === 'DISABLED') {
    // 플래그가 안 켜졌다는 뜻이다. 조용히 통과시키면 C군이 B군과 같은 것을 재게 되므로
    // 진짜 에러로 센다 — 이 군에서 tikkit_real_errors는 0이어야 한다.
    realErrors.add(true, { step: 'queue_enter' });
    return;
  }

  admitted.add(state.state === 'ADMITTED');

  if (state.state !== 'ADMITTED') {
    // 대기 중이거나 만료다. 폴링 간격과 순번만 기록하고 iteration을 끝낸다.
    // sleep을 넣지 않는다 — arrival-rate executor가 페이싱을 통제하므로 여기서 기다리면
    // 설정한 도착률이 그대로 깨진다.
    pollInterval.add(state.pollAfterSeconds);
    if (state.position !== null && state.position !== undefined) {
      position.add(state.position);
    }
    return;
  }

  // 3) 입장했다. 여기서부터는 booking-spike.js와 같은 경로다.
  const seatRes = apiGet(`/schedules/${scheduleId}/seats`, 'seatmap');

  // **입장권을 받은 직후의 403은 버그다.** 줄을 안 선 사람의 403과 상태코드·ErrorCode가
  // 같아서 tikkit_queue_pass_denied만 보면 구분이 안 된다(lib/api.js 주석 참조).
  // 032가 고친 "입장했는데도 401" 버그와 같은 자리다 — 거기선 세션이 안 실렸고,
  // 여기선 입장권 검증이 깨지는 경우를 잡는다.
  check(seatRes.res, {
    '입장 후 좌석 조회: 403이 아니다': function (res) {
      return res.status !== 403;
    },
  });

  const seats = data(seatRes);
  if (seatCount(seats) === 0) {
    return;
  }

  const byGrade = groupAvailableByGrade(seats);
  const gradeIds = Object.keys(byGrade);
  if (gradeIds.length === 0) {
    // 전 좌석이 HELD/SOLD다. 재고 소진은 정상 종료 조건이지만, 이게 잦으면 켠 회차 수가
    // 적어 좌석이 먼저 마른 것이다 — 측정이 "선점 처리량"이 아니라 "매진 조회"가 된다
    // (98-reset-reservations.sql 주석의 사례). 04-enable-queue.sql로 회차를 늘린다.
    return;
  }

  const ticketGradeId = Number(pick(gradeIds));
  const pool = byGrade[ticketGradeId];
  const quantity = Math.min(1 + Math.floor(Math.random() * 4), pool.length);
  const offset = Math.floor(Math.random() * (pool.length - quantity + 1));
  const seatIds = pool.slice(offset, offset + quantity);

  const created = apiPost(
    '/reservations',
    { scheduleId: scheduleId, ticketGradeId: ticketGradeId, quantity: quantity, seatIds: seatIds },
    'reservation_create'
  );

  // 입장한 사람의 선점 성공률이 이 Task의 수용 기준 중 하나다 — 대기열의 목적은
  // "줄을 세우는 것"이 아니라 "입장한 사람은 끝까지 간다"이기 때문이다.
  holdSuccess.add(created.res.status === 201);

  // 선점에도 가드가 걸려 있다(ReservationService가 requireAdmitted를 직접 부른다).
  // 좌석 조회를 통과하고 선점에서 403이면 그 사이에 입장권이 만료된 것이고, pass-ttl이
  // 15분이라 90초 측정에서는 일어날 수 없다 — 나면 설정이나 시계가 틀린 것이다.
  check(created.res, {
    '선점: 201 또는 정상 거절(409)': function (res) {
      return res.status === 201 || res.status === 409;
    },
  });

  const reservation = data(created);
  if (!reservation) {
    return;
  }

  // 4) 결제 또는 취소 — PENDING을 반드시 해소한다.
  //    결제·취소는 대기열 보호 대상이 아니다(이미 좌석을 잡은 사람이 거래를 못 끝내면 안 된다).
  if (Math.random() < PAY_RATIO) {
    apiPost(`/reservations/${reservation.id}/payments`, { method: 'CARD' }, 'reservation_pay');
  } else {
    apiPost(`/reservations/${reservation.id}/cancel`, null, 'reservation_cancel');
  }
}
