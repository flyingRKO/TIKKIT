// 시나리오 5: 대기열 상태 API 단독 포화 — Task 033
//
// 재는 것은 R_poll 하나다: "상태 조회가 p95 안에 처리되는 최대 RPS".
//
// ── 왜 이걸 따로 재는가 ──────────────────────────────────────────────────────
// 폴링 간격(rank<100 → 2초 / <1,000 → 5초 / 그 외 10초)에 근거가 없다. 031이 "10만 명이
// 2초 고정이면 50,000 RPS"라는 계산으로 백오프를 넣었지만, 백오프를 적용한 실제 부하가
// 얼마이고 그걸 서버가 버티는지는 재지 않았다.
//
// 로컬에서 10만 명을 만들 수 없으니 **단위 처리량을 재고 공식을 역으로 푼다.** 백오프가
// 닫힌 식이라 가능하다:
//
//   대기 N명일 때 상태 API RPS
//     = min(N,100)/2 + min(max(N-100,0),900)/5 + max(N-1000,0)/10
//
//   N = 100,000 → 50 + 180 + 9,900 = 10,130 RPS   (백오프 없으면 50,000)
//
// R_poll을 재서 N_max를 역산한다. R_poll < 10,130이면 10초 구간이 모자란 것이고, 여유가
// 크면 2초 구간을 넓혀 체감을 개선할 여지가 있다. 판정 기준이 p95가 아니라 **용량**이다.
//
// ── 측정이 성립하려면 VU가 계속 "대기 중"이어야 한다 ─────────────────────────
// 입장하면 상태 조회가 ADMITTED로 빠져 ZRANK를 거치지 않는다. 그러면 재는 것이
// "순번 계산 비용"이 아니라 "ZSCORE 한 번"이 된다. 그래서 둘 중 하나를 같이 한다:
//   1) 입장을 거의 멈춘다 — QUEUE_ADMIT_BATCH_SIZE=1로 띄운다
//   2) 앞에 합성 멤버를 심어 실제 VU가 뒤에 머물게 한다 (군 E의 ZADD 파이프라인)
// 2)를 쓰면 rank ≥ 1,000 구간(폴링 10초)도 같이 밟는다 — 032가 미검증으로 남긴 구간이다.
//
// 결과의 tikkit_queue_waiting_ratio가 1에 가깝지 않으면 위 준비가 안 된 것이고,
// 그 실행의 R_poll은 쓸 수 없다.
//
// 실행:
//   docker compose -f docker-compose.prod.yml --profile load run --rm k6 \
//     run -o experimental-prometheus-rw -e QUEUE_EXPECTED=1 -e SCHEDULE_IDS=<id들> \
//     -e STAGE_PROFILE=breakpoint -e MAX_RATE=2000 \
//     --summary-export=/results/t033-poll-breakpoint-1.json /scripts/queue-poll.js
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { apiGet, apiPost, data } from './lib/api.js';
import { ensureLogin, ACCOUNT_COUNT } from './lib/accounts.js';
import { collectScheduleIds } from './lib/catalog.js';
import { SYSTEM_TAGS, arrivalStages, thresholdsFor } from './lib/config.js';

const PEAK_RATE = Number(__ENV.PEAK_RATE || 200);
const MAX_VUS = Number(__ENV.MAX_VUS || 300);

// 상태 응답이 WAITING인 비율. **1에 가깝지 않으면 이 실행의 R_poll은 무효다**
// (위 "측정이 성립하려면" 참조).
const waitingRatio = new Rate('tikkit_queue_waiting_ratio');

// 백엔드가 지시한 간격. 어느 구간을 밟았는지 기록한다.
const pollInterval = new Trend('tikkit_queue_poll_after_seconds');
const position = new Trend('tikkit_queue_position');

export const options = {
  scenarios: {
    poll: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      // 상태 조회는 Redis 왕복 두어 번이라 iteration이 짧다 — 같은 도착률을 적은 VU로
      // 낸다. 그래도 포화 구간에서는 iteration이 길어지므로 상한은 넉넉히 둔다.
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
  return { scheduleIds: collectScheduleIds() };
}

// 모듈 스코프는 VU별로 독립이고 iteration 경계를 넘어 살아있다 (lib/accounts.js와 같은 패턴).
let joined = false;

export default function (setupData) {
  if (!ensureLogin()) {
    return;
  }

  // **회차를 VU마다 고정한다.** 매번 무작위로 고르면 VU가 회차를 옮겨다니며 꼬리로
  // 재진입해서 순번이 계속 리셋되고, 그러면 rank 분포가 측정하려는 것과 달라진다.
  const scheduleId = setupData.scheduleIds[(__VU - 1) % setupData.scheduleIds.length];

  // 첫 iteration은 줄을 서고 끝낸다. 상태 조회에 POST를 섞으면 측정하려는 RPS가
  // 상태 API 단독 수치가 아니게 된다.
  if (!joined) {
    const entered = apiPost(`/queue/schedules/${scheduleId}/entries`, null, 'queue_enter');
    joined = entered.ok;
    return;
  }

  const res = apiGet(`/queue/schedules/${scheduleId}/status`, 'queue_status');
  const state = data(res);
  if (!state) {
    return;
  }

  waitingRatio.add(state.state === 'WAITING');
  pollInterval.add(state.pollAfterSeconds);
  if (state.position !== null && state.position !== undefined) {
    position.add(state.position);
  }

  check(res.res, {
    '상태 조회: 200': function (r) {
      return r.status === 200;
    },
    // 큐에 없는 회원도 200 + EXPIRED로 답하는 것이 계약이다(QueueController Javadoc).
    // DISABLED가 오면 04-enable-queue.sql이 안 돌았거나 다른 회차를 때리고 있다.
    '상태 조회: 대기열이 켜져 있다': function () {
      return state.state !== 'DISABLED';
    },
  });

  // sleep을 넣지 않는다. pollAfterSeconds를 그대로 기다리면 "실제 사용자 한 명의 폴링"을
  // 재현하는 것이고, 이 스크립트가 재려는 것은 **서버가 초당 몇 건을 처리하는가**다.
  // 사용자 수는 그 RPS에서 위 공식으로 역산한다.
}
