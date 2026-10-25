// 시나리오 2: 좌석 조회 — Task 027
//
// 이 프로젝트에서 가장 무거운 읽기다. 회차 전체 좌석을 페이지네이션 없이 평면 배열로
// 한 번에 내려주므로(ScheduleController), 2,000석이면 응답이 수백 KB다.
// 004_3에서 좌석맵 p95가 3.07ms로 나왔지만 그때는 schedule_seats가 dev 시드 규모였다.
//
// 실행:
//   docker compose -f docker-compose.prod.yml --profile load run --rm k6 \
//     run -o experimental-prometheus-rw --summary-export=/results/seatmap.json /scripts/seatmap.js
// ⚠ 이 스크립트는 로그인하지 않는다 (좌석 조회가 permitAll이다 — Task 022가 "자리를 보고
// 나서 가입하는 흐름이 정상"이라고 일부러 열었다). 그래서 **대기열을 켠 회차에는 쓸 수 없다** —
// QueuePassInterceptor가 memberId == null로 401을 던지고, lib/api.js는 401을 "세션 유실 =
// Task 026 재발 신호"로 분류한다. 증상이 "대기열 문제"가 아니라 "세션 스케일링 회귀"로 보여서
// 원인 추적이 가장 오래 걸린다.
//
// 대기열을 켠 상태의 좌석 조회는 queue-spike.js가 (로그인 + 입장권을 받고) 잰다.
// 이 스크립트를 쓰는 군(캐시 재판정 등)은 대기열을 끈 상태여야 한다.
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { apiGet, data } from './lib/api.js';
import { collectScheduleIds, seatCount } from './lib/catalog.js';
import {
  BASE_THRESHOLDS,
  DURATION,
  RAMP_DOWN,
  RAMP_UP,
  SYSTEM_TAGS,
  pick,
  think,
} from './lib/config.js';

const VUS = Number(__ENV.VUS || 30);

// 응답 크기를 따로 기록한다. p95가 올라갔을 때 "쿼리가 느려진 것"과
// "좌석 수가 늘어 바디가 커진 것"을 구분하려면 둘을 같이 봐야 한다.
const seatmapBytes = new Trend('tikkit_seatmap_bytes');
const seatmapSeats = new Trend('tikkit_seatmap_seats');

export const options = {
  scenarios: {
    seatmap: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_UP, target: VUS },
        { duration: DURATION, target: VUS },
        { duration: RAMP_DOWN, target: 0 },
      ],
      gracefulRampDown: '10s',
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
  const scheduleId = pick(setupData.scheduleIds);
  const result = apiGet(`/schedules/${scheduleId}/seats`, 'seatmap');
  const seats = data(result);

  // seatCount로 세는 이유: 응답이 null일 수 있다 (apiGet이 실패하면 data()가 null을 준다).
  // seats.length로 바로 세면 TypeError가 나고, 그게 k6에서는 iteration 실패로만 보인다.
  const count = seatCount(seats);

  check(result.res, {
    '좌석맵: 좌석이 있다': function () {
      return count > 0;
    },
  });

  if (count > 0) {
    seatmapBytes.add(result.res.body.length);
    seatmapSeats.add(count);
  }

  // 좌석을 고르는 데 걸리는 시간. 좌석맵은 한 번 열고 한참 들여다보는 화면이라
  // think time을 다른 시나리오보다 길게 둔다.
  think(1.0, 4.0);
}
