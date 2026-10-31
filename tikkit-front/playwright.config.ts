import { defineConfig, devices } from "@playwright/test";

// E2E_BASE_URL이 밖에서 주어지면 "이미 떠 있는 FE를 쓰라"는 뜻으로 해석한다
// (Task 024: docker-compose.prod.yml로 띄운 FE 컨테이너를 대상으로 돌릴 때).
const EXTERNAL_BASE_URL = process.env.E2E_BASE_URL;
const BASE_URL = EXTERNAL_BASE_URL ?? "http://localhost:3000";

export default defineConfig({
  testDir: "e2e",
  // 같은 DB(재고, 회원)를 공유하는 실제 BE를 쓰므로 순차 실행한다
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry",
    // 로컬 TLS는 self-signed다 (Task 039_1 — nginx/generate-local-cert.sh).
    // 체인이 없어서 이게 없으면 https://localhost 대상 실행이 net::ERR_CERT_AUTHORITY_INVALID로
    // 전부 떨어진다. baseURL이 로컬 스택뿐이라 이 완화가 다른 데 닿지 않고,
    // CI는 http로 돌아서 영향이 없다.
    ignoreHTTPSErrors: true,
  },
  projects: [
    {
      // 회귀 테스트용. 스크린샷 캡처와 성능 측정 스펙은 제외한다
      name: "e2e",
      testIgnore: ["**/screenshots.spec.ts", "**/perf/**"],
      use: { ...devices["Desktop Chrome"] },
    },
    {
      // README용 화면 캡처. CI에서는 돌리지 않고 로컬에서 `npm run screenshots`로 실행한다
      name: "screenshots",
      testMatch: "**/screenshots.spec.ts",
      use: { ...devices["Desktop Chrome"] },
    },
    {
      // 성능 측정 (Task 030). 회귀 테스트가 아니라 수치를 뽑는 용도라 CI에서는 돌리지 않고
      // 로컬에서 `npm run perf`로 실행한다.
      //
      // prod 스택(docker-compose.prod.yml, nginx 경유)을 대상으로 해야 의미가 있어서
      // E2E_BASE_URL을 반드시 넘겨야 한다. 기본값 localhost:3000으로 조용히 떨어지면
      // dev 서버나 옛 번들을 재게 되므로(004 10절 "함정 4") 스펙이 직접 검사해서 실패시킨다.
      name: "perf",
      testMatch: "**/perf/**/*.spec.ts",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  // E2E_BASE_URL이 없을 때만 FE를 직접 띄운다. BE(dev 프로필)와 DB는 밖에서 미리 띄워 둬야 한다
  // (시드 데이터가 dev 프로필에서만 들어감).
  //
  // E2E_BASE_URL이 있으면 webServer를 아예 끈다. reuseExistingServer로는 안 되는데,
  // CI에서는 그 값이 false가 되어 `npm run start`가 3000번 포트를 두고 충돌하고,
  // 로컬에서도 Playwright가 종료 시점에 남의 서버(컨테이너)를 죽이려 들기 때문이다.
  webServer: EXTERNAL_BASE_URL
    ? undefined
    : {
        command: "npm run start",
        url: BASE_URL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      },
});
