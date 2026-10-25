// 부하 테스트 계정 풀 (Task 027)
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { apiPost } from './api.js';

// load-test/sql/03-loadtest-members.sql이 만든 계정 수와 맞춰야 한다.
// Task 033에서 500 -> 2,000으로 올렸다 — 포화점을 찾으려면 maxVUs 상한이 서버보다
// 먼저 걸리면 안 된다(min(300, ACCOUNT_COUNT) 참조).
export const ACCOUNT_COUNT = Number(__ENV.ACCOUNT_COUNT || 2000);

const PASSWORD = 'Password1!';

// SharedArray는 VU마다 복제되지 않고 한 번만 메모리에 올라간다.
// 일반 배열로 두면 VU 수만큼 복제돼서, VU를 늘릴 때 서버보다 부하 생성기가 먼저 터진다.
export const accounts = new SharedArray('accounts', function () {
  const out = [];
  for (let i = 1; i <= ACCOUNT_COUNT; i++) {
    // padStart 대신 수동 패딩 — k6의 JS 런타임은 Node가 아니라서
    // 최신 표준 메서드를 전제하지 않는 쪽이 안전하다.
    const no = ('000' + i).slice(-4);
    out.push({ email: `loadtest${no}@tikkit.com`, password: PASSWORD });
  }
  return out;
});

// VU 번호로 계정을 고른다.
//
// 두 VU가 같은 계정을 쓰면 uk_reservations_pending_member_grade(member_id + ticket_grade_id에
// PENDING 1건) 때문에 한쪽이 409 DUPLICATE_PENDING_RESERVATION을 계속 받는다.
// VU 수가 ACCOUNT_COUNT를 넘으면 그때부터 계정이 겹치므로, 예매 시나리오의 maxVUs는
// 계정 수 이하로 둔다.
export function accountForVU() {
  return accounts[(__VU - 1) % accounts.length];
}

// 모듈 스코프는 VU별로 독립이라 "이 VU가 로그인했는지"를 여기 둘 수 있다.
let loggedIn = false;

// VU당 한 번만 로그인한다.
//
// 매 iteration마다 로그인하면 두 가지가 망가진다 — BCrypt 검증(의도적으로 느리다)이
// 측정 구간에 계속 섞이고, 세션이 iteration마다 새로 생겨 Redis 쓰기가 실제보다 많아진다.
// 세션 타임아웃이 30분이라 90초짜리 테스트에서는 한 번으로 충분하다.
//
// 로그인 요청에는 step='login' 태그가 붙으므로 엔드포인트별 집계에서 분리해서 볼 수 있다.
export function ensureLogin() {
  if (loggedIn) {
    return true;
  }
  const account = accountForVU();
  const result = apiPost('/auth/login', account, 'login');
  check(result.res, {
    'login: 200': function (res) {
      return res.status === 200;
    },
  });
  loggedIn = result.ok;
  return loggedIn;
}
