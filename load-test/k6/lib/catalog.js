// 측정 대상 회차 id 수집 (Task 027)
import http from 'k6/http';
import { API } from './config.js';

// 회차 id를 하드코딩하지 않는 이유: IDENTITY 채번이라 DB를 다시 만들면 값이 바뀐다.
// dev 시드가 켜져 있는지에 따라서도 시작 번호가 달라진다.
// 그래서 setup()에서 실제 API로 긁어 온다 — 스크립트가 어떤 DB에서도 그대로 돈다.
//
// 공연 1,000개를 전부 순회하지 않는다. 상세 조회가 1,000번 들어가면 setup이 기본
// setupTimeout(60초)을 넘기고, 측정 구간 바로 앞에 불필요한 CPU 스파이크를 남긴다.
// 표본 60개 회차면 좌석맵/예매 대상이 한 회차에 쏠리지 않을 만큼 충분하다.
// keyword로 부하 데이터만 집는다.
//
// 그냥 status=ON_SALE로 긁으면 dev 시드 회차가 섞인다 — 목록 정렬이 start_date asc이고
// 시드 공연(now + 15~27일)이 부하 공연(now + 31~33일)보다 앞서기 때문에 1~2페이지가 전부
// 시드다. 시드는 공연장이 달라 좌석 수가 350~3,150석으로 흔들려서 좌석맵 p95를
// 회차마다 다른 응답 크기로 재게 된다.
// keyword는 title containsIgnoreCase라서 '[LOAD] 부하테스트 공연 NNNN'만 걸린다.
const KEYWORD = __ENV.CATALOG_KEYWORD || '부하테스트';

// 회차 id를 직접 지정하는 경로 (Task 033).
//
// 대기열 측정에서는 API로 긁어오면 안 된다 — 목록에는 ON_SALE 회차가 3,000개 들어 있고
// queue_enabled가 켜진 것은 그중 10개뿐이다. 긁어온 60개를 쓰면 대부분이 DISABLED를 받아
// 대기열을 거치지 않고 통과하고, **"대기열 켠 군"과 "안 켠 군"이 한 측정에 섞인다.**
// 04-enable-queue.sql이 마지막에 찍는 id 목록을 그대로 넘긴다.
const EXPLICIT_IDS = __ENV.SCHEDULE_IDS || '';

export function collectScheduleIds(sampleSize) {
  if (EXPLICIT_IDS) {
    const ids = EXPLICIT_IDS.split(',')
      .map(function (v) {
        return Number(v.trim());
      })
      .filter(function (v) {
        return v > 0;
      });
    if (ids.length === 0) {
      throw new Error(`SCHEDULE_IDS를 파싱하지 못했다: '${EXPLICIT_IDS}'`);
    }
    console.log(`setup: SCHEDULE_IDS로 지정된 회차 ${ids.length}개를 쓴다`);
    return ids;
  }

  const target = sampleSize || Number(__ENV.SAMPLE_SCHEDULES || 60);
  const scheduleIds = [];

  for (let page = 0; page < 10 && scheduleIds.length < target; page++) {
    const listRes = http.get(
      `${API}/performances?status=ON_SALE&keyword=${encodeURIComponent(KEYWORD)}&page=${page}&size=20`,
      // setup 요청에도 name을 고정한다. 안 붙이면 k6가 URL 전체를 name 라벨로 쓰고,
      // 상세 조회가 표본 수만큼 돌면서 측정과 무관한 시계열이 수십 개 생긴다.
      { tags: { name: 'setup_list' } }
    );
    if (listRes.status !== 200) {
      throw new Error(`공연 목록 조회 실패: status=${listRes.status}`);
    }
    const list = listRes.json();
    const content = list && list.data ? list.data.content : null;
    if (!content || content.length === 0) {
      break;
    }

    for (let i = 0; i < content.length && scheduleIds.length < target; i++) {
      const detailRes = http.get(`${API}/performances/${content[i].id}`, {
        tags: { name: 'setup_detail' },
      });
      if (detailRes.status !== 200) {
        continue;
      }
      const detail = detailRes.json();
      const schedules = detail && detail.data ? detail.data.schedules : null;
      if (!schedules) {
        continue;
      }
      for (let j = 0; j < schedules.length && scheduleIds.length < target; j++) {
        scheduleIds.push(schedules[j].id);
      }
    }
  }

  if (scheduleIds.length === 0) {
    throw new Error(
      `ON_SALE 회차를 찾지 못했다(keyword='${KEYWORD}'). load-test/sql/01~02를 실행했는지 확인한다.`
    );
  }
  console.log(`setup: 회차 ${scheduleIds.length}개를 측정 대상으로 잡았다`);
  return scheduleIds;
}

// 좌석맵 응답의 좌석 수. 응답이 null일 수 있어서 호출부마다 가드를 두지 않도록 묶었다.
export function seatCount(seats) {
  return seats ? seats.length : 0;
}

// 좌석맵 응답에서 AVAILABLE 좌석을 등급별로 묶는다.
//
// 선점 요청은 좌석이 전부 같은 ticketGradeId여야 하고(ReservationCreateRequest 검증),
// quantity와 seatIds.length가 정확히 같아야 한다. 등급별로 모아두면 둘을 한 번에 맞출 수 있다.
export function groupAvailableByGrade(seats) {
  const byGrade = {};
  for (let i = 0; i < seats.length; i++) {
    const seat = seats[i];
    if (seat.status !== 'AVAILABLE') {
      continue;
    }
    if (!byGrade[seat.ticketGradeId]) {
      byGrade[seat.ticketGradeId] = [];
    }
    byGrade[seat.ticketGradeId].push(seat.id);
  }
  return byGrade;
}
