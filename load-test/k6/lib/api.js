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

// 대기열 입장권 거절률 (Task 033).
//
// QUEUE_EXPECTED를 켜면 403이 "진짜 에러"에서 빠지는데, 그렇다고 세지 않으면 안 된다 —
// 입장권 없이 좌석을 찌른 403은 정상이고 **입장한 뒤에 받은 403은 버그인데, 둘이 같은
// 상태코드·같은 ErrorCode로 온다.** 그래서 여기서 비율만 따로 남기고,
// "ADMITTED 직후의 403"은 queue-spike.js가 check로 실패 처리한다.
//
// 이 값이 높은 것 자체는 정상이다. 대기열을 켠 회차에 줄을 안 서고 좌석을 요청하면
// 전부 403이고, 그게 대기열이 일하고 있다는 뜻이다.
export const queuePassDenied = new Rate('tikkit_queue_pass_denied');

// 대기열 403을 "의도된 거절"로 받아들일지. **기본값이 꺼짐이고, 그게 중요하다.**
//
// 켜야 하는 이유: 대기열을 켠 군에서는 좌석 조회 403이 정상이다(QueuePassInterceptor와
// ReservationService 가드가 의도적으로 던진다 — 409 SOLD_OUT과 같은 성격). 이게 없으면
// tikkit_real_errors 임계값(rate<0.001)이 측정 시작 직후 터지고, 요청마다 console.error가
// 찍혀서 k6 자체가 느려진다(측정 오염).
//
// **그런데 무조건 켜면 안 된다.** 대기열을 끈 군에서 403이 나오면 그건 플래그가 샌 것이고,
// 무조건 허용하면 그 군이 초록색으로 조용히 통과한다 — 031이 QueueFlagRegistry에서 겪은
// "켜지지 않은 환경에서 조용히 통과하는 테스트"와 같은 함정이다. 그래서 측정자가 군마다
// "이 군은 403이 나와도 정상"이라고 선언하게 한다.
//
//   대기열 켠 군:  -e QUEUE_EXPECTED=1
//   대기열 끈 군:  넘기지 않는다 (403이 나면 임계값이 터져야 한다)
const QUEUE_EXPECTED = __ENV.QUEUE_EXPECTED === '1';

// 정상적인 거절로 취급하는 (상태코드 -> ErrorCode) 목록.
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
  const listed = (EXPECTED_REJECTIONS[res.status] || []).indexOf(code) >= 0;
  // 403은 QUEUE_EXPECTED를 켠 군에서만 의도된 거절이다 (위 주석 참조).
  const queueRejection =
    QUEUE_EXPECTED &&
    res.status === 403 &&
    (code === 'QUEUE_PASS_REQUIRED' || code === 'QUEUE_PASS_EXPIRED');
  const expected = listed || queueRejection;
  const real = !ok && !expected;

  realErrors.add(real, { step: label });

  // 403만 따로 센다 (위 queuePassDenied 주석 참조). 대기열이 꺼진 군에서는 0이어야 하고,
  // 0이 아니면 플래그가 의도와 다르게 켜져 있다는 신호다.
  if (res.status === 403) {
    queuePassDenied.add(true, { step: label });
  } else if (ok) {
    queuePassDenied.add(false, { step: label });
  }

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

// 대기열 이탈(DELETE /queue/schedules/{id}/entries)에 필요하다.
// request()는 처음부터 메서드를 받았는데 GET/POST만 export돼 있었다 — 이탈을 호출할 수가
// 없었고, 그러면 입장권을 반납하지 못해 pass-ttl(15분) 동안 같은 계정이 계속 ADMITTED로
// 돌아온다. 그 상태로 측정을 반복하면 2회차부터 대기열을 타지 않는다
// (load-test/redis/reset-queue.sh가 같은 문제를 Redis 쪽에서 닫는다).
export function apiDelete(path, label) {
  return request('DELETE', path, null, label);
}

// 응답 바디에서 ApiResponse.data를 꺼낸다. 없으면 null.
export function data(result) {
  if (!result.json || !result.json.data) {
    return null;
  }
  return result.json.data;
}
