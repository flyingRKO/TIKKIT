import { defineConfig, devices } from "@playwright/test";

const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

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
  },
  projects: [
    {
      // 회귀 테스트용. 스크린샷 캡처 스펙은 제외한다
      name: "e2e",
      testIgnore: "**/screenshots.spec.ts",
      use: { ...devices["Desktop Chrome"] },
    },
    {
      // README용 화면 캡처. CI에서는 돌리지 않고 로컬에서 `npm run screenshots`로 실행한다
      name: "screenshots",
      testMatch: "**/screenshots.spec.ts",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  // FE만 띄운다. BE(dev 프로필)와 DB는 밖에서 미리 띄워 둬야 한다 (시드 데이터가 dev 프로필에서만 들어감)
  webServer: {
    command: "npm run start",
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
