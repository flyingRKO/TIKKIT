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
//
// ── THINK_MIN / THINK_MAX로 끌 수 있다 (Task 033) ───────────────────────────
// ramping-vus 시나리오(browse·browse-skewed·seatmap)를 **포화까지 올리려면** think를 꺼야 한다.
// 기본값으로는 iteration 3.74초 중 HTTP가 6ms라서, VU 600명에서도 p95가 2ms이고 처리량이
// VU에 정확히 비례한다 — 재는 것이 서버 용량이 아니라 sleep이다.
//
// 008이 캐시를 "지연이 아니라 용량"으로 채택하면서 그 용량을 재지 못한 이유가 이것이다
// (BE CPU 4.2%에서 측정했다). 포화를 만들려면 VU를 수천 명 띄우는 대신 think를 0으로 둔다.
//
// 기본값은 바꾸지 않는다. 006·008·029의 수치가 이 값으로 측정됐고, 바꾸면 그 비교가 깨진다.
const THINK_MIN = Number(__ENV.THINK_MIN !== undefined ? __ENV.THINK_MIN : 0.5);
const THINK_MAX = Number(__ENV.THINK_MAX !== undefined ? __ENV.THINK_MAX : 2.0);

// ★ 인자보다 위에 있는 전역 스위치가 필요했다 ★
// seatmap.js는 think(1.0, 4.0)으로 **인자를 직접 넘긴다**(좌석맵은 한 번 열고 한참 들여다보는
// 화면이라 일부러 길게 뒀다). 인자를 넘기면 기본값이 덮이지 않으므로 THINK_MAX=0이 무시된다 —
// 증상은 "포화를 만들었는데 처리량이 97 req/s다"로만 나타나고, 스윕을 다 돌린 뒤에야
// 이상해 보인다(실제로 한 번 겪었다). 그래서 인자보다 우선하는 스위치를 둔다.
const THINK_OFF = __ENV.THINK_MAX !== undefined && Number(__ENV.THINK_MAX) <= 0;

export function think(min = THINK_MIN, max = THINK_MAX) {
  if (THINK_OFF || max <= 0) {
    return;
  }
  sleep(min + Math.random() * (max - min));
}

// --- 도착률 stages (Task 033) ---
//
// A군(포화점 탐색)과 B·C군(스파이크 비교)이 **같은 시나리오 파일**을 쓰면서 stages만 달라야
// 한다. 파일을 쪼개면 stages 한 줄 차이에 스크립트 전체가 중복되고, 둘이 갈라지는 순간
// "같은 시나리오를 다른 부하로 돌렸다"는 전제가 조용히 깨진다.

// 'spike'(기본) | 'breakpoint'
export const STAGE_PROFILE = __ENV.STAGE_PROFILE || 'spike';

// --- breakpoint 전용 ---
// 계단 수와 계단당 체류 시간. 체류 시간이 짧으면 안 된다 — Prometheus scrape_interval이
// 10초라서 계단마다 Gauge 샘플이 몇 개는 잡혀야 Hikari active의 피크를 놓치지 않는다
// (DURATION 주석의 004_3 4.4절 사례가 그것이다). 40초면 계단마다 4개다.
const STEP_COUNT = Number(__ENV.STEP_COUNT || 6);
const STEP_DURATION = __ENV.STEP_DURATION || '40s';
// 계단 사이를 올리는 구간. 짧게 둬서 "올라가는 중"인 샘플이 계단 평균을 오염시키지 않게 한다.
const STEP_RAMP = __ENV.STEP_RAMP || '5s';
// 마지막 계단의 도착률. 서버가 여기 닿기 전에 무너지는 것이 정상이다.
const MAX_RATE = Number(__ENV.MAX_RATE || 300);

// 기존 거동: ramp-up -> 유지 -> ramp-down.
export function rampingStages(peakRate) {
  return [
    { duration: RAMP_UP, target: peakRate },
    { duration: DURATION, target: peakRate },
    { duration: RAMP_DOWN, target: 0 },
  ];
}

// 계단식 상승. 포화점(p95가 꺾이는 도착률)을 한 번의 실행에서 찾는다.
//
// 계단마다 stages가 두 개인 이유: k6의 stage는 이전 target에서 새 target까지 **선형으로**
// 움직인다. 하나만 두면 평평한 구간이 없어서 "이 도착률에서의 p95"를 집어낼 수 없다.
// 짧게 올린 뒤(STEP_RAMP) 길게 유지(STEP_DURATION)해야 계단이 된다.
export function breakpointStages() {
  const stages = [];
  for (let i = 1; i <= STEP_COUNT; i++) {
    const target = Math.round((MAX_RATE * i) / STEP_COUNT);
    stages.push({ duration: STEP_RAMP, target: target });
    stages.push({ duration: STEP_DURATION, target: target });
  }
  // 0으로 떨어뜨려 마지막 계단의 in-flight 요청이 집계에 반쯤 걸리는 것을 막는다.
  stages.push({ duration: RAMP_DOWN, target: 0 });
  return stages;
}

// STAGE_PROFILE에 따라 고른다. 시나리오 파일은 이 함수만 부르면 된다.
export function arrivalStages(peakRate) {
  return STAGE_PROFILE === 'breakpoint' ? breakpointStages() : rampingStages(peakRate);
}

// breakpoint 프로필의 임계값.
//
// **일부러 서버를 무너뜨리는 측정이라 BASE_THRESHOLDS를 쓰면 매번 빨개지고 아무 정보도 주지
// 않는다.** 027이 지연 임계값을 비워둔 것과 같은 판단이다 — 근거 없는 숫자를 박으면 신호가
// 아니라 소음이 된다. 포화점은 임계값이 아니라 **결과 JSON의 계단별 p95**에서 읽는다.
//
// 그래도 checks는 남긴다. 스크립트 버그(좌석 수 불일치 등)와 서버 포화를 구분하는 유일한
// 장치이고, 이게 없으면 "무너진 것"과 "애초에 잘못 쏜 것"이 같은 그림으로 보인다.
export const BREAKPOINT_THRESHOLDS = {
  checks: ['rate>0.90'],
};

// 엔드포인트별 지표를 요약에 나오게 하는 스위치 (Task 033).
//
// ★ 이건 임계값이 아니라 집계 스위치다 ★
// lib/api.js가 요청마다 name 태그를 붙여 "엔드포인트별 p95를 뽑는 라벨"이라고 적어뒀는데,
// **k6 요약에는 그 분해가 하나도 나오지 않았다.** k6는 태그가 붙은 하위 지표를 임계값으로
// 선언한 것만 집계하고 요약에 내보낸다. 그래서 선언 자체가 필요하다.
//
// 값을 60초로 둔 이유: 절대 걸리지 않게 하려는 것이다. 027이 "근거 없는 숫자를 박으면 매번
// 빨개지고 아무 정보도 주지 않는다"며 지연 임계값을 비워뒀는데, 그 원칙을 지키면서 집계만
// 켜려면 통과가 보장된 값을 넣어야 한다. **이 숫자는 판정 기준이 아니다.**
//
// 이게 있어야 "좌석맵 응답 시간" 하나를 뽑아 tikkit.seatmap.build(조립 시간)와 뺄 수 있다.
// 전체 http_req_duration은 선점·결제까지 섞인 평균이라 좌석맵 비용을 말해주지 않는다.
const SUBMETRIC_SWITCHES = {
  'http_req_duration{name:seatmap}': ['p(95)<60000'],
  'http_req_duration{name:reservation_create}': ['p(95)<60000'],
  'http_req_duration{name:queue_status}': ['p(95)<60000'],
  'http_req_duration{name:queue_enter}': ['p(95)<60000'],
};

// 프로필에 맞는 임계값. 시나리오 파일은 이걸 쓴다.
export function thresholdsFor() {
  const base = STAGE_PROFILE === 'breakpoint' ? BREAKPOINT_THRESHOLDS : BASE_THRESHOLDS;
  return Object.assign({}, base, SUBMETRIC_SWITCHES);
}

// 배열에서 하나를 무작위로 집는다. 비어 있으면 null.
export function pick(arr) {
  if (!arr || arr.length === 0) {
    return null;
  }
  return arr[Math.floor(Math.random() * arr.length)];
}
