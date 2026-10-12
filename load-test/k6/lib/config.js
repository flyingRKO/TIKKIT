// k6 공통 설정 (Task 027)
import { sleep } from 'k6';

// compose 서비스로 돌면 nginx가 서비스명으로 잡힌다. 호스트 바이너리로 돌릴 때만
// BASE_URL=http://localhost 을 넘긴다.
const BASE_URL = __ENV.BASE_URL || 'http://nginx';

export const API = `${BASE_URL}/api/v1`;

// 부하 유지 시간.
//
// Prometheus scrape_interval이 10초라서 Gauge(Hikari 풀, 힙)는 10초마다 찍히는 스냅샷이다.
// 짧은 부하를 걸면 샘플이 두세 개뿐이고, 마침 유휴인 순간에 걸리면 피크를 통째로 놓친다
// (004_3 4.4절: 18초 부하에서 Hikari active 최대가 0으로 나왔다).
// 기본 90초는 그 9배라 유지 구간에서만 샘플이 9개 이상 잡힌다.
export const DURATION = __ENV.DURATION || '90s';
export const RAMP_UP = __ENV.RAMP_UP || '15s';
export const RAMP_DOWN = __ENV.RAMP_DOWN || '10s';

// 1차 측정용 임계값.
//
// 지연(p95) 임계값을 일부러 넣지 않았다. 이 Task가 그 기준선을 "만드는" 단계라서
// 근거 없는 숫자를 박으면 매번 빨개지고 아무 정보도 주지 않는다.
// 측정이 끝나면 실측값 + 여유분으로 확정해 이 객체에 넣고, 그 값이 Task 028/029의
// 회귀 감지선이 된다.
export const BASE_THRESHOLDS = {
  // HTTP 상태 기준 실패율. 409를 실패로 세므로 예매 시나리오에서는 참고용이다.
  http_req_failed: ['rate<0.01'],
  // 진짜 에러만 세는 쪽 (lib/api.js 참조). 이게 올라가면 서버가 터진 것이다.
  tikkit_real_errors: ['rate<0.001'],
  checks: ['rate>0.99'],
};

// Prometheus로 내보낼 시스템 태그.
//
// k6 기본값에는 url, error, tls_version, subproto, service가 포함된다. 그중 **url이 문제다** —
// 요청 URL 전체가 라벨로 들어가서 /schedules/80/seats 같은 경로 변수마다 시계열이 하나씩 생긴다.
// name 태그를 lib/api.js에서 템플릿 이름으로 고정해도 url은 따로 붙으므로 여기서 빼야 한다.
//
// Task 025가 서버 쪽에서 했던 것과 같은 작업이다 — 그때는 히스토그램 버킷 범위를 잘라
// 카디널리티를 줄였고(004_3 4.2절), 이번엔 부하 생성기 쪽 라벨을 자른다.
//
// error를 빼고 error_code를 남기는 이유: error는 자유 문자열이라 메시지마다 시계열이 생기고,
// error_code는 유한한 숫자 집합이라 안전하다.
export const SYSTEM_TAGS = [
  'proto',
  'status',
  'method',
  'name',
  'group',
  'check',
  'error_code',
  'scenario',
  'expected_response',
];

// think time.
//
// 요청을 붙여 쏘면 같은 VU 수에서 비현실적으로 높은 RPS가 나온다. 실제 사용자는 응답을
// 읽고 다음 행동을 하므로 그 사이를 비워야 "VU 50명"이 "동시 접속자 50명"에 가까워진다.
// 주의: arrival-rate executor에서는 쓰지 않는다 — executor가 페이싱을 직접 통제하므로
// sleep을 넣으면 설정한 도착률이 그대로 깨진다.
export function think(min = 0.5, max = 2.0) {
  sleep(min + Math.random() * (max - min));
}

// 배열에서 하나를 무작위로 집는다. 비어 있으면 null.
export function pick(arr) {
  if (!arr || arr.length === 0) {
    return null;
  }
  return arr[Math.floor(Math.random() * arr.length)];
}
