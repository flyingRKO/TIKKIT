import fs from "node:fs";
import path from "node:path";
import { expect, test, type Page } from "@playwright/test";

// README용 화면 캡처. 회귀 테스트가 아니라서 CI에서는 돌리지 않고 `npm run screenshots`로 로컬에서만 실행한다.
// 실제 BE(dev 프로필, 시드 포함)가 떠 있어야 한다. 결제·완료 화면을 찍으려고 시드 계정으로 실제 예매를 한 번 하고,
// 마지막에 그 예약을 취소해서 재고를 되돌린다.

const OUT_DIR = path.resolve(__dirname, "../../docs/images");
const DESKTOP = { width: 1280, height: 800 };
const MOBILE = { width: 360, height: 800 };

test.use({ viewport: DESKTOP, colorScheme: "light" });

async function capture(page: Page, fileName: string, options: { fullPage?: boolean } = {}) {
  // 폰트·이미지가 다 뜬 뒤에 찍는다
  await page.waitForLoadState("networkidle");
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({
    path: path.join(OUT_DIR, fileName),
    animations: "disabled",
    fullPage: options.fullPage ?? false,
  });
}

test("README용 주요 화면 캡처", async ({ page }) => {
  fs.mkdirSync(OUT_DIR, { recursive: true });

  await test.step("메인 (비로그인)", async () => {
    await page.goto("/");
    await expect(page.getByRole("link", { name: "로그인" }).first()).toBeVisible();
    await capture(page, "01-home.png");
  });

  await test.step("시드 계정으로 로그인", async () => {
    await page.goto("/login");
    await page.getByLabel("이메일").fill("user@tikkit.com");
    await page.getByLabel("비밀번호").fill("Password1!");
    await page.getByRole("button", { name: "로그인" }).click();
    await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();
  });

  await test.step("공연 상세 (티켓 선택)", async () => {
    await page.goto("/performances?status=ON_SALE");
    await page.locator('main a[href^="/performances/"]').first().click();
    await expect(page).toHaveURL(/\/performances\/\d+$/);

    await page.getByRole("button", { name: /잔여 \d+석/ }).first().click();
    await page.getByRole("button", { name: "수량 증가" }).click();
    await capture(page, "02-performance-detail.png", { fullPage: true });

    // 모바일은 하단 고정 CTA가 보이는 첫 화면만 찍는다
    await page.setViewportSize(MOBILE);
    await capture(page, "03-performance-detail-mobile.png");
    await page.setViewportSize(DESKTOP);
  });

  await test.step("좌석 배치도", async () => {
    await page.getByRole("link", { name: "좌석 선택" }).click();
    await expect(page).toHaveURL(/\/seats\?/);
    // 좌석을 골라서 요약 패널이 채워진 상태로 찍는다
    await page.getByRole("checkbox").first().click();
    await page.getByRole("checkbox").nth(1).click();
    await expect(page.getByText("선택한 좌석 2/2")).toBeVisible();
    await capture(page, "04-seat-map.png", { fullPage: true });

    await page.setViewportSize(MOBILE);
    await capture(page, "05-seat-map-mobile.png");
    await page.setViewportSize(DESKTOP);
  });

  let bookingUrl = "";
  await test.step("결제 화면", async () => {
    await page.getByRole("button", { name: "예매하기" }).click();
    await expect(page).toHaveURL(/\/booking\/\d+$/);
    bookingUrl = page.url();
    await expect(page.getByRole("heading", { name: "결제하기", level: 1 })).toBeVisible();
    // 결제 버튼이 폼 맨 아래라서 전체 높이로 찍는다
    await capture(page, "06-booking-payment.png", { fullPage: true });
  });

  await test.step("결제 완료 화면", async () => {
    await page.getByLabel("카카오페이").check();
    await page.getByRole("button", { name: /원 결제하기$/ }).click();
    await expect(page.getByRole("heading", { name: "예매가 완료되었습니다" })).toBeVisible();
    await capture(page, "07-booking-complete.png");
  });

  await test.step("예매 내역", async () => {
    await page.getByRole("link", { name: "예매 내역 보기" }).click();
    await expect(page.getByRole("heading", { name: "내 예매 내역" })).toBeVisible();
    await capture(page, "08-my-reservations.png", { fullPage: true });
  });

  await test.step("정리: 방금 만든 예약을 취소해 재고를 되돌린다", async () => {
    const reservationId = bookingUrl.match(/\/booking\/(\d+)$/)![1];
    await page.goto(`/my/reservations/${reservationId}`);
    await page.getByRole("button", { name: "예매 취소" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "취소하기" }).click();
    await expect(page).toHaveURL(/\/my\/reservations$/);
  });
});
