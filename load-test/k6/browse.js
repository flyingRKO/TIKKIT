// 시나리오 1: 탐색 (목록 -> 상세 -> 등급) — Task 027
//
// 실행:
//   docker compose -f docker-compose.prod.yml --profile load run --rm k6 \
//     run -o experimental-prometheus-rw --summary-export=/results/browse.json /scripts/browse.js
import { check } from 'k6';
import { apiGet, data } from './lib/api.js';
import {
  BASE_THRESHOLDS,
  DURATION,
  RAMP_DOWN,
  RAMP_UP,
  SYSTEM_TAGS,
  pick,
  think,
} from './lib/config.js';

const VUS = Number(__ENV.VUS || 50);

export const options = {
  scenarios: {
    browse: {
      // ramping-vus = closed model. 사용자 수를 늘려가며 서버가 버티는지 본다.
      // 서버가 느려지면 부하도 같이 줄어드는 특성이 있어서 "평상시 트래픽"을 흉내내는 데 맞다.
      // 오픈런처럼 서버 상태와 무관하게 요청이 들어오는 상황은 booking-spike.js가 맡는다.
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
  // discardResponseBodies를 켜지 않는다. 목록 응답에서 공연 id를 꺼내 상세로 넘어가야 하고,
  // 바디를 버리면 응답 크기가 측정에서 빠져 실제보다 빠른 수치가 나온다.
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  // 1) 목록 — 페이지를 무작위로 넘긴다.
  //
  // 항상 0페이지만 보면 Postgres 버퍼 캐시에 같은 블록만 올라가서 실제보다 좋은 수치가 나온다.
  // 그러면 Task 029(캐싱)의 before가 이미 캐시된 상태로 측정되는 셈이 된다.
  // 공연 1,000개 / size 20 = 50페이지라 전 구간을 고르게 훑는다.
  const page = Math.floor(Math.random() * 50);
  const list = apiGet(`/performances?status=ON_SALE&page=${page}&size=20`, 'performance_list');

  const listData = data(list);
  check(list.res, {
    '목록: content가 있다': function () {
      return listData !== null && listData.content && listData.content.length > 0;
    },
  });
  if (!listData || !listData.content) {
    return;
  }
  think();

  // 2) 상세 — 회차 목록이 상세 응답 안에 들어있다 (별도 엔드포인트가 없다).
  const performance = pick(listData.content);
  if (!performance) {
    return;
  }
  const detail = apiGet(`/performances/${performance.id}`, 'performance_detail');
  const detailData = data(detail);
  check(detail.res, {
    '상세: 회차가 있다': function () {
      return detailData !== null && detailData.schedules && detailData.schedules.length > 0;
    },
  });
  if (!detailData || !detailData.schedules) {
    return;
  }
  think();

  // 3) 등급 — 실제 사용자는 좌석을 열기 전에 가격을 먼저 본다.
  const schedule = pick(detailData.schedules);
  if (!schedule) {
    return;
  }
  apiGet(`/schedules/${schedule.id}/ticket-grades`, 'ticket_grades');
  think();
}
