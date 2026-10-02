import { expect, test } from "@playwright/test";

// 회원가입 → 로그인 → 공연 선택 → 선점 → 결제 → 예매 내역 → 취소까지 한 번에 확인하는 핵심 시나리오.
// 실제 BE(dev 프로필, 시드 데이터 포함)가 떠 있어야 한다. 공연 id는 시드 날짜에 따라 달라질 수 있어서 고정하지 않는다.
// 매번 새 회원으로 가입하므로 같은 등급 PENDING 중복 선점(409)에 걸리지 않고, 마지막에 취소해서 재고를 되돌린다.

test("가입 → 로그인 → 선점 → 결제 → 취소 흐름", async ({ page }) => {
  const runId = Date.now();
  const email = `e2e-${runId}@tikkit.com`;
  const password = "Password1!";
  const name = "이투이";

  let reservationNo = "";

  await test.step("회원가입하면 로그인 화면으로 이동한다", async () => {
    await page.goto("/signup");
    await page.getByLabel("이메일").fill(email);
    await page.getByLabel("비밀번호").fill(password);
    await page.getByLabel("이름").fill(name);
    await page.getByLabel("휴대폰 번호").fill("010-1234-5678");
    await page.getByRole("button", { name: "회원가입" }).click();

    // 가입 후 자동 로그인은 하지 않는다
    await expect(page).toHaveURL(/\/login/);
  });

  await test.step("로그인하면 헤더에 이름과 로그아웃이 보인다", async () => {
    await page.getByLabel("이메일").fill(email);
    await page.getByLabel("비밀번호").fill(password);
    await page.getByRole("button", { name: "로그인" }).click();

    await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();
    await expect(page.getByText(`${name}님`)).toBeVisible();
  });

  await test.step("예매중 공연 상세로 들어간다", async () => {
    await page.goto("/performances?status=ON_SALE");
    await expect(page.getByRole("heading", { name: "공연 목록", level: 1 })).toBeVisible();

    // 공연 카드는 카드 전체가 /performances/{id} 링크다 (페이지네이션은 ?page= 라서 겹치지 않는다)
    await page.locator('main a[href^="/performances/"]').first().click();
    await expect(page).toHaveURL(/\/performances\/\d+$/);
  });

  await test.step("등급을 골라 예매하면 결제 화면으로 이동한다", async () => {
    // 판매 중인 첫 회차는 기본으로 선택돼 있다. 매진이 아닌(잔여 N석 표시) 첫 등급을 고른다
    const grade = page.getByRole("button", { name: /잔여 \d+석/ }).first();
    await grade.click();
    await expect(grade).toHaveAttribute("aria-pressed", "true");

    await page.getByRole("button", { name: "예매하기" }).click();
    await expect(page).toHaveURL(/\/booking\/\d+$/);
    await expect(page.getByRole("heading", { name: "결제하기", level: 1 })).toBeVisible();
  });

  await test.step("모의 결제를 마치면 완료 화면이 뜬다", async () => {
    await page.getByLabel("카카오페이").check();
    await page.getByRole("button", { name: /원 결제하기$/ }).click();

    await expect(page).toHaveURL(/\/booking\/\d+\/complete$/);
    await expect(page.getByRole("heading", { name: "예매가 완료되었습니다" })).toBeVisible();

    // 예약번호 형식: TK{yyMMdd}-{6자리}
    const mainText = await page.locator("main").innerText();
    const matched = mainText.match(/TK\d{6}-\d{6}/);
    expect(matched, "완료 화면에 예약번호가 있어야 한다").not.toBeNull();
    reservationNo = matched![0];
  });

  await test.step("예매 내역에서 방금 예약을 찾아 상세로 들어간다", async () => {
    await page.getByRole("link", { name: "예매 내역 보기" }).click();
    await expect(page).toHaveURL(/\/my\/reservations$/);

    const item = page.getByRole("link", { name: new RegExp(reservationNo) });
    await expect(item).toContainText("예매 완료");
    await item.click();

    await expect(page.getByRole("heading", { name: "예매 상세" })).toBeVisible();
    await expect(page.getByText("결제 완료")).toBeVisible();
  });

  await test.step("예매를 취소하면 목록에서 취소 상태로 보인다", async () => {
    await page.getByRole("button", { name: "예매 취소" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText("예매를 취소할까요?")).toBeVisible();
    await dialog.getByRole("button", { name: "취소하기" }).click();

    await expect(page).toHaveURL(/\/my\/reservations$/);
    const item = page.getByRole("link", { name: new RegExp(reservationNo) });
    await expect(item).toContainText("취소");
    await expect(item).not.toContainText("예매 완료");
  });
});
