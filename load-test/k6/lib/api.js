// API 호출 래퍼 + 에러 분류 (Task 027)
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { API } from './config.js';

// k6 내장 http_req_failed는 HTTP 상태만 본다. 그런데 이 서비스는 좌석 경쟁에서 밀린 요청에
// 409 SOLD_OUT을 돌려준다 — 서버가 올바르게 막아낸 것이지 장애가 아니다.
// 오픈런 스파이크에서 http_req_failed가 40%로 나와도 정상일 수 있다는 뜻이다.
//
// 그래서 "서버가 잘 막은 것"과 "서버가 터진 것"을 가르는 메트릭을 따로 둔다.
// 이게 curl + xargs 측정과 갈리는 핵심 지점이다 — curl은 상태코드만 세므로
// 이 구분을 아예 할 수 없었다.
export const realErrors = new Rate('tikkit_real_errors');

// 선점 성공률. 재고가 남아 있는데도 선점이 안 되면 경쟁이 과도한 것이다.
export const holdSuccess = new Rate('tikkit_hold_success');

// 정상적인 거절로 취급하는 (상태코드 -> ErrorCode) 목록.
// 전부 ReservationService가 의도적으로 던지는 비즈니스 예외다.
const EXPECTED_REJECTIONS = {
  409: [
    'SOLD_OUT', // 좌석 경쟁에서 밀렸다 (조건부 UPDATE가 0건을 반환)
    'DUPLICATE_PENDING_RESERVATION', // 같은 회원 + 등급에 PENDING이 이미 있다
    'RESERVATION_EXPIRED', // 10분 홀드가 지난 뒤 결제를 시도했다
    'INVALID_STATUS_TRANSITION', // 이미 결제/취소된 예약을 다시 건드렸다
  ],
};

// VU 전용 쿠키 자르.
//
// k6는 **iteration이 끝날 때마다 VU의 기본 쿠키 자르를 비운다.** 기본 자르에 맡기면
// 1번 iteration에서 받은 JSESSIONID가 2번 iteration으로 넘어가지 않아 인증이 필요한
// 요청이 전부 401이 된다 (실제로 그렇게 나왔다 — 선점 요청 132건이 전부 401 UNAUTHORIZED였다).
//
// 모듈 스코프는 VU별로 독립이고 iteration 경계를 넘어 살아있다. 여기에 자르를 두고
// 모든 요청에 명시적으로 넘기면 "VU당 한 번 로그인"이 의도대로 동작한다.
//
// 참고: 이 401은 Task 026의 세션 불일치와 증상이 같다. 그래서 lib/api.js가 401을
// "진짜 에러"로 분류한다 — 실제 측정에서 401이 보이면 둘 중 하나이므로 반드시 원인을 가려야 한다.
const vuJar = new http.CookieJar();

// 응답 바디를 파싱한다. 실패하면 null — 500 HTML 에러 페이지 같은 경우다.
function parse(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

// 응답을 "정상 / 의도된 거절 / 진짜 에러"로 가른다.
//
// 진짜 에러로 보는 것들:
//   500                     서버 예외
//   400 VALIDATION_ERROR    스크립트 버그 (quantity != seatIds.length 등)
//   400 BOOKING_NOT_OPEN    데이터 문제 (load-test/sql을 안 돌렸거나 회차를 잘못 골랐다)
//   404 NOT_FOUND           좌석과 등급 조합이 어긋났다
//   401 UNAUTHORIZED        쿠키가 유실됐다 = 세션 불일치 재발 신호 (Task 026)
export function classify(res, label) {
  const json = parse(res);
  const code = json && json.code ? json.code : null;
  const ok = res.status >= 200 && res.status < 300;
  const expected = (EXPECTED_REJECTIONS[res.status] || []).indexOf(code) >= 0;
  const real = !ok && !expected;

  realErrors.add(real, { step: label });

  if (real) {
    // 진짜 에러는 0건이어야 하므로 로그가 넘칠 걱정이 없다. 나면 원인을 바로 봐야 한다.
    console.error(
      `[${label}] status=${res.status} code=${code} body=${String(res.body).slice(0, 300)}`
    );
  }

  return { ok: ok, code: code, real: real, json: json, res: res };
}

function request(method, path, payload, label) {
  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: {
      // 엔드포인트별 p95를 뽑는 라벨.
      step: label,
      // name은 k6의 "URL 그룹핑" 태그다. 비워두면 k6가 **요청 URL 전체를** 넣는다.
      // 그러면 /schedules/80/seats, /schedules/81/seats, /reservations/12345/payments가
      // 각각 별도 시계열이 되고, 2분짜리 측정 3회에 k6_http_req_duration_p95 시리즈가
      // 3,255개까지 늘어났다(실측). 측정하려고 붙인 모니터링이 측정 때문에 망가지는 경로다.
      // 여기서 템플릿 이름으로 고정해 경로 변수를 라벨에서 지운다.
      name: label,
    },
    // iteration을 넘어 세션을 유지한다 (위 vuJar 주석 참조).
    jar: vuJar,
  };
  const url = `${API}${path}`;
  const res = http.request(method, url, payload === null ? null : JSON.stringify(payload), params);

  const result = classify(res, label);
  const checks = {};
  checks[`${label}: 처리됨`] = function () {
    return !result.real;
  };
  check(res, checks);

  return result;
}

export function apiGet(path, label) {
  return request('GET', path, null, label);
}

export function apiPost(path, payload, label) {
  return request('POST', path, payload, label);
}

// 응답 바디에서 ApiResponse.data를 꺼낸다. 없으면 null.
export function data(result) {
  if (!result.json || !result.json.data) {
    return null;
  }
  return result.json.data;
}
