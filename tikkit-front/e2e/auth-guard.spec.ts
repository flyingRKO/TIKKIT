import { expect, test } from "@playwright/test";

// proxy.ts가 /booking, /my 아래 경로를 JSESSIONID 쿠키 유무로 막는지 확인한다.
// 쿠키가 아예 없는 새 브라우저 컨텍스트에서 시작하므로 따로 로그아웃할 필요가 없다.

test("로그인 없이 마이페이지에 들어가면 로그인 화면으로 보내고 원래 경로를 기억한다", async ({ page }) => {
  await page.goto("/my/reservations");

  await expect(page).toHaveURL(/\/login\?redirect=(%2F|\/)my(%2F|\/)reservations/);
  await expect(page.getByRole("heading", { name: "로그인", level: 1 })).toBeVisible();
});

test("로그인 없이 결제 화면에 들어가도 로그인 화면으로 보낸다", async ({ page }) => {
  await page.goto("/booking/1");

  await expect(page).toHaveURL(/\/login\?redirect=/);
});
