// 시나리오 1-B: 탐색 (트래픽이 인기 공연에 쏠리는 분포) — Task 029
//
// browse.js와 같은 엔드포인트를 때리지만 **대상 선택 분포만** 다르다.
//
// 왜 따로 만들었나: browse.js는 공연 1,000개와 50페이지를 **균일 무작위**로 훑는다.
// 그건 의도된 설계였다 — Task 027이 Postgres 버퍼 캐시에 같은 블록만 올라가는 걸 피하려고
// 그렇게 짰고(006 10절), 덕분에 Task 028의 EXPLAIN 측정이 유리하게 왜곡되지 않았다.
// 그런데 조회 캐시를 재는 데는 그 분포가 **최악의 조건**이다. 상세 조회가 1,000개 공연에
// 고르게 퍼지면 적중률이 구조적으로 낮게 나오고, 실제 예매 서비스의 트래픽은 그 반대다 —
// 오픈 임박한 몇 개 공연에 쏠린다.
//
// 그래서 균일 분포(browse.js)와 편중 분포(이 파일)를 나란히 재서 "캐시 효과가 트래픽 분포에
// 얼마나 민감한가"를 보여준다. **이 수치는 006/007 기준선과 비교할 수 없다** — 그쪽은
// browse.js로만 쟀다. 독립 수치로만 읽는다.
//
// 실행:
//   docker compose -f docker-compose.prod.yml --profile load run --rm --no-deps k6 \
//     run -o experimental-prometheus-rw --summary-export=/results/browse-skewed.json \
//     /scripts/browse-skewed.js
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

// 쏠림의 세기. HOT_RATIO만큼의 요청이 앞쪽 HOT_PAGES 페이지로 간다.
//
// 0.8 / 2페이지로 둔 이유: 목록 정렬이 start_date asc라 앞 페이지가 "곧 시작하는 공연"이고,
// 실제로 트래픽이 쏠리는 쪽이 거기다. 50페이지 중 2페이지면 공연 40개 규모이고,
// 상세 조회 캐시가 그 40개에 집중된다.
// Zipf를 정확히 구현하지 않았다 — 두 구간(hot/tail) 분할이면 "쏠리면 어떻게 달라지나"를
// 보여주는 데 충분하고, 분포 파라미터가 하나 더 늘면 수치를 해석하기 어려워진다.
const HOT_RATIO = Number(__ENV.HOT_RATIO || 0.8);
const HOT_PAGES = Number(__ENV.HOT_PAGES || 2);
const TOTAL_PAGES = Number(__ENV.TOTAL_PAGES || 50);

export const options = {
  scenarios: {
    browse_skewed: {
      // browse.js와 같은 executor·단계를 쓴다. 분포만 다르게 두려는 것이므로
      // 나머지 조건을 하나라도 바꾸면 두 수치를 나란히 놓을 수 없다.
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
  systemTags: SYSTEM_TAGS,
  // 라벨을 browse.js와 **같게** 유지한다(performance_list / performance_detail / ticket_grades).
  // 그래야 Grafana에서 같은 패널로 두 실행을 비교할 수 있다. 실행 구분은 K6_RUN_LABEL로 한다.
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
};

/** HOT_RATIO 확률로 앞쪽 HOT_PAGES 중 하나, 나머지는 꼬리 구간에서 고른다. */
function pickPage() {
  if (Math.random() < HOT_RATIO) {
    return Math.floor(Math.random() * HOT_PAGES);
  }
  return HOT_PAGES + Math.floor(Math.random() * (TOTAL_PAGES - HOT_PAGES));
}

export default function () {
  // 1) 목록
  const page = pickPage();
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

  // 2) 상세 — 목록에서 고르므로 쏠림이 그대로 전파된다.
  // 캐시 적중률이 가장 크게 달라지는 지점이 여기다(공연 1,000개 → 사실상 40개).
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

  // 3) 등급 — 가격은 캐시되고 잔여 수량은 매번 센다. 히트해도 쿼리 1문장이 남는 경로다.
  const schedule = pick(detailData.schedules);
  if (!schedule) {
    return;
  }
  apiGet(`/schedules/${schedule.id}/ticket-grades`, 'ticket_grades');
  think();
}
