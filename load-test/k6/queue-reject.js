// 시나리오 6: 대기열 거절 경로가 DB 커넥션을 잡는가 — Task 033
//
// 재는 것 하나다: **입장권 없이 POST /reservations를 때렸을 때 Hikari 커넥션을 쓰는가.**
//
// ── 왜 이걸 재는가 ──────────────────────────────────────────────────────────
// ReservationService.create()는 @Transactional이고, 그 **첫 줄**이
// queueGateway.requireAdmitted()다. 대기열의 목적은 "DB를 지키는 것"인데, 거절이
// 트랜잭션 안에서 일어나므로 커넥션을 먼저 잡고 나서 던지면 **지키려던 자원을 거절이
// 그대로 소모한다.** 오픈런에서 거절이 전체 요청의 대부분이라 영향이 작지 않다.
//
// Hibernate가 커넥션을 지연 획득하면 쓰지 않고, 트랜잭션 시작 시점에 잡으면 쓴다.
// 설정으로 추정할 수 있는 문제가 아니라(Spring Boot가 handling_mode를 직접 세팅한다)
// 지표를 보는 쪽이 확실하다 — hikaricp_connections_acquire_seconds_count의 증가분이다.
//
// ── 측정이 성립하는 조건 ────────────────────────────────────────────────────
// 1) QUEUE_ADMIT_BATCH_SIZE=0  — 아무도 입장하지 않는다(peekWaiting이 count<=0을 막아준다).
//    하나라도 입장하면 그 사람은 정상 경로로 DB를 쓰고, 그게 증가분에 섞인다.
// 2) 회차 플래그 on + QUEUE_ENABLED=true
// 3) 전부 403이어야 한다. 201이 섞이면 1)이 깨진 것이다.
//
// ★ 좌석 조회를 하지 않는다 ★
// seatIds가 유효하지 않아도 된다 — requireAdmitted()가 **DB에 닿기 전에** 던지므로
// 좌석 id는 쓰이지 않는다. 좌석맵을 먼저 받으면 그 요청이 커넥션을 쓰고(그건 인터셉터가
// 막아 403이지만) 측정하려는 경로가 흐려진다.
//
// 실행:
//   docker compose -f docker-compose.prod.yml --profile load run --rm --no-deps k6 \
//     run -e QUEUE_EXPECTED=1 -e SCHEDULE_IDS=21 -e PEAK_RATE=100 -e DURATION=60s \
//     --summary-export=/results/t033-reject.json /scripts/queue-reject.js
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { apiPost } from './lib/api.js';
import { ensureLogin, ACCOUNT_COUNT } from './lib/accounts.js';
import { collectScheduleIds } from './lib/catalog.js';
import { SYSTEM_TAGS, arrivalStages, thresholdsFor } from './lib/config.js';

const PEAK_RATE = Number(__ENV.PEAK_RATE || 100);
const MAX_VUS = Number(__ENV.MAX_VUS || 300);

// 403으로 거절된 비율. **1에 가깝지 않으면 이 실행은 무효다** (위 조건 3).
const rejected = new Rate('tikkit_reject_ratio');

export const options = {
  scenarios: {
    reject: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
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
  return { scheduleIds: collectScheduleIds() };
}

export default function (setupData) {
  if (!ensureLogin()) {
    return;
  }

  const scheduleId = setupData.scheduleIds[(__VU - 1) % setupData.scheduleIds.length];

  // 줄을 서지 않는다. 큐에 없는 회원도 requireAdmitted()에서 같은 403을 받으므로
  // 진입 POST를 섞을 이유가 없고, 섞으면 측정 대상이 아닌 Redis 왕복이 늘어난다.
  const res = apiPost(
    '/reservations',
    { scheduleId: scheduleId, ticketGradeId: 1, quantity: 1, seatIds: [1] },
    'reservation_create'
  );

  rejected.add(res.res.status === 403);

  check(res.res, {
    // 201이면 누군가 입장한 것이고(batch가 0이 아니다), 400이면 검증에서 끊긴 것이다.
    // 둘 다 측정 전제가 깨졌다는 신호다.
    '예매 요청: 403으로 거절된다': function (r) {
      return r.status === 403;
    },
  });
}
