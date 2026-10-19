import { expect, test } from "@playwright/test";
import {
  median,
  printTable,
  requireBaseUrl,
  requireSeatMapTarget,
  round,
  seatMapPath,
} from "./target";

/**
 * 좌석 클릭 -> DOM 반영 시간 (Task 030 측정 단계).
 *
 * 004 13절이 "클릭 -> DOM 반영 5~8ms(3,000석)"를 남겼지만 어떻게 쟀는지는 적혀 있지 않다.
 * 이번에 측정 계약을 못 박는다.
 *
 *   t0  좌석 rect에 click 이벤트를 디스패치한 직후
 *   t1  그 좌석의 aria-checked 속성 변경이 DOM에 커밋된 시각 (React commit)
 *   t2  그 다음 프레임 직전 (requestAnimationFrame 콜백)
 *
 * aria-checked를 보는 이유는 seat-map.tsx의 선택 가능 레이어가 선택 상태를 그 속성으로
 * 표현하기 때문이다(role="checkbox" + aria-checked).
 *
 * 측정을 전부 페이지 안에서 하는 이유: page.click() + expect()로 재면 Playwright의 WS
 * 왕복(수 ms)이 섞여서 5~8ms 규모의 신호를 통째로 삼킨다. 그 차이가 얼마인지는 마지막
 * 표("측정 장치 비용")에서 따로 보여준다.
 *
 * 이 스펙은 수치를 뽑는 용도다. 통과/실패로 성능을 판정하지 않는다(임계값 없음).
 */

const TOGGLE_SAMPLES = 20;

interface ClickTiming {
  commit: number;
  frame: number;
}

interface Measurement {
  seatCounts: { total: number; selectable: number };
  firstClick: ClickTiming | null;
  toggles: Array<{ select: ClickTiming | null; deselect: ClickTiming | null }>;
  depths: Array<{ selectedBefore: number; timing: ClickTiming | null }>;
}

test.describe("좌석 클릭 응답성", () => {
  test("클릭 -> DOM 반영 시간을 페이지 내부 시계로 잰다", async ({ page }) => {
    test.setTimeout(120_000);

    const baseUrl = requireBaseUrl();
    const target = requireSeatMapTarget();

    await page.goto(`${baseUrl}${seatMapPath(target)}`, { waitUntil: "networkidle" });
    await page.evaluate(() => document.fonts.ready);

    // 하이드레이션 전에 클릭하면 아무 일도 안 일어나고, 그게 "타임아웃"으로만 보여서
    // 느린 것과 구분되지 않는다. 좌석이 실제로 반응할 때까지 기다린 뒤 측정에 들어간다.
    const firstSeat = page.locator('rect[role="checkbox"]').first();
    await expect(firstSeat).toHaveAttribute("aria-checked", "false");

    const measurement = await page.evaluate<Measurement, { toggleSamples: number; maxSelect: number }>(
      async ({ toggleSamples, maxSelect }) => {
        const seats = Array.from(
          document.querySelectorAll<SVGRectElement>('rect[role="checkbox"]')
        );

        /** 한 번의 클릭을 재고 {commit, frame}을 돌려준다. 반응이 없으면 null. */
        function measureClick(seat: SVGRectElement): Promise<ClickTiming | null> {
          return new Promise((resolve) => {
            let t0 = 0;
            const observer = new MutationObserver(() => {
              const t1 = performance.now();
              observer.disconnect();
              clearTimeout(timeoutId);
              requestAnimationFrame(() => {
                resolve({ commit: t1 - t0, frame: performance.now() - t0 });
              });
            });
            // 선택 매수가 찼을 때처럼 클릭이 무시되는 경우가 있다(seat-selector.tsx).
            // 그때는 영원히 안 끝나므로 상한을 둔다.
            const timeoutId = setTimeout(() => {
              observer.disconnect();
              resolve(null);
            }, 2_000);

            observer.observe(seat, { attributes: true, attributeFilter: ["aria-checked"] });
            t0 = performance.now();
            seat.dispatchEvent(new MouseEvent("click", { bubbles: true }));
          });
        }

        // 1) 하이드레이션 직후 첫 클릭. 이후 클릭과 따로 본다.
        const firstClick = await measureClick(seats[0]);
        if (firstClick !== null) {
          await measureClick(seats[0]); // 선택 해제해서 깊이 0으로 되돌린다
        }

        // 2) 서로 다른 좌석을 선택 -> 해제로 토글. 선택 개수는 계속 0~1이라
        //    "선택이 쌓일수록 느려지는가"와 분리된다.
        const toggles: Measurement["toggles"] = [];
        for (let index = 1; index <= toggleSamples && index < seats.length; index += 1) {
          const seat = seats[index];
          const select = await measureClick(seat);
          const deselect = select === null ? null : await measureClick(seat);
          toggles.push({ select, deselect });
        }

        // 3) 선택 깊이별 비용. selectedSeatIds가 배열이라 좌석마다 includes()로 O(n)을
        //    돌고, 선택 가능 레이어에는 memo가 없다. 깊이가 깊어질수록 비싸지는지 본다.
        const depths: Measurement["depths"] = [];
        for (let depth = 0; depth < maxSelect; depth += 1) {
          const seat = seats[seats.length - 1 - depth];
          const timing = await measureClick(seat);
          depths.push({ selectedBefore: depth, timing });
        }
        // 선택 해제해서 원래 상태로
        for (let depth = maxSelect - 1; depth >= 0; depth -= 1) {
          await measureClick(seats[seats.length - 1 - depth]);
        }

        return {
          seatCounts: {
            total: document.querySelectorAll("rect").length,
            selectable: seats.length,
          },
          firstClick,
          toggles,
          depths,
        };
      },
      { toggleSamples: TOGGLE_SAMPLES, maxSelect: target.quantity }
    );

    expect(
      measurement.seatCounts.selectable,
      "선택 가능한 좌석이 거의 없다 — 등급이나 좌석 상태를 확인해라"
    ).toBeGreaterThan(TOGGLE_SAMPLES + target.quantity);
    expect(measurement.firstClick, "첫 클릭이 아무 반응도 만들지 않았다").not.toBeNull();

    const selectTimings = measurement.toggles
      .map((toggle) => toggle.select)
      .filter((timing): timing is ClickTiming => timing !== null);
    expect(selectTimings.length, "토글 측정이 대부분 실패했다").toBeGreaterThan(TOGGLE_SAMPLES / 2);

    const commits = selectTimings.map((timing) => timing.commit);
    const frames = selectTimings.map((timing) => timing.frame);

    printTable("좌석 클릭 -> DOM 반영 (페이지 내부 시계, ms)", [
      {
        구간: "첫 클릭 (하이드레이션 직후)",
        "commit(t1-t0)": round(measurement.firstClick!.commit),
        "frame(t2-t0)": round(measurement.firstClick!.frame),
        표본: 1,
      },
      {
        구간: `토글 선택 (중앙값, 좌석 ${selectTimings.length}개)`,
        "commit(t1-t0)": round(median(commits)),
        "frame(t2-t0)": round(median(frames)),
        표본: selectTimings.length,
      },
      {
        구간: "토글 선택 (최소)",
        "commit(t1-t0)": round(Math.min(...commits)),
        "frame(t2-t0)": round(Math.min(...frames)),
        표본: selectTimings.length,
      },
      {
        구간: "토글 선택 (최대)",
        "commit(t1-t0)": round(Math.max(...commits)),
        "frame(t2-t0)": round(Math.max(...frames)),
        표본: selectTimings.length,
      },
    ]);

    printTable(
      "선택 깊이별 클릭 비용 (ms)",
      measurement.depths.map((entry) => ({
        "선택 좌석 수": `${entry.selectedBefore} -> ${entry.selectedBefore + 1}`,
        "commit(t1-t0)": entry.timing === null ? "무시됨" : round(entry.timing.commit),
        "frame(t2-t0)": entry.timing === null ? "무시됨" : round(entry.timing.frame),
      }))
    );

    // 측정 장치 비용. 같은 동작을 Playwright API로 하면 얼마가 더 붙는지 보여준다.
    // 이 값과 위 표의 차이가 "WS 왕복 + 실제 포인터 입력" 몫이다.
    const outsideTimings: number[] = [];
    for (let index = 0; index < 5; index += 1) {
      const seat = page.locator('rect[role="checkbox"]').nth(index + 1);
      const startedAt = Date.now();
      await seat.click();
      await expect(seat).toHaveAttribute("aria-checked", "true");
      outsideTimings.push(Date.now() - startedAt);
      await seat.click();
      await expect(seat).toHaveAttribute("aria-checked", "false");
    }

    printTable("측정 장치 비용 — Playwright API 경유 (ms)", [
      {
        구간: "page.click() + 속성 확인 (중앙값)",
        왕복: round(median(outsideTimings)),
        표본: outsideTimings.length,
        비고: "페이지 내부 시계 값과의 차이가 측정 장치 몫이다",
      },
    ]);

    printTable("측정 대상", [
      {
        "좌석 rect 총": measurement.seatCounts.total,
        "선택 가능": measurement.seatCounts.selectable,
        매수상한: target.quantity,
      },
    ]);
  });
});
