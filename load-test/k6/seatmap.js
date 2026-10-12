// 시나리오 2: 좌석 조회 — Task 027
//
// 이 프로젝트에서 가장 무거운 읽기다. 회차 전체 좌석을 페이지네이션 없이 평면 배열로
// 한 번에 내려주므로(ScheduleController), 2,000석이면 응답이 수백 KB다.
// 004_3에서 좌석맵 p95가 3.07ms로 나왔지만 그때는 schedule_seats가 dev 시드 규모였다.
//
// 실행:
//   docker compose -f docker-compose.prod.yml --profile load run --rm k6 \
//     run -o experimental-prometheus-rw --summary-export=/results/seatmap.json /scripts/seatmap.js
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { apiGet, data } from './lib/api.js';
import { collectScheduleIds } from './lib/catalog.js';
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

  check(result.res, {
    '좌석맵: 좌석이 있다': function () {
      return seats !== null && seats.length > 0;
    },
  });

  if (seats) {
    seatmapBytes.add(result.res.body.length);
    seatmapSeats.add(seats.length);
  }

  // 좌석을 고르는 데 걸리는 시간. 좌석맵은 한 번 열고 한참 들여다보는 화면이라
  // think time을 다른 시나리오보다 길게 둔다.
  think(1.0, 4.0);
}
