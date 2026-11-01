/**
 * 좌석맵 초기 렌더 비용의 **산포**와 path/rect 교차점 (Task 041).
 *
 * ★ 왜 별도 스펙인가 ★
 *   `dom-memory.spec.ts`는 **페이지 생애 누적**을 재므로(하이드레이션 직후 / 선택 후 두 점)
 *   같은 page로 반복하면 회차가 섞인다. 회차마다 context를 새로 만들어야 하는데, 그러려면
 *   그 스펙의 구조를 바꿔야 한다 — 035가 "`seat-interaction.spec.ts` 계약 무변경"을 지킨
 *   이유와 같아서 건드리지 않고 이쪽을 새로 만들었다.
 *
 * ★ 왜 산포를 먼저 재는가 ★
 *   009_2가 "같은 변경이 2,000석에서는 회귀이고 10,000석에서는 개선"을 발견했다
 *   (RecalcStyle 18.71 -> 31.59ms / 71.84 -> 29.20ms, §6.4 "하이드레이션 직후"). 그런데
 *   **그 네 점은 각 단일 점이고**(회차·범위 표기가 없다) `improvements/README.md`의 축별
 *   산포 표에 RecalcStyle·Layout 행이 **0건**이다. 즉 ±68.8%가 노이즈 밖인지 알 근거가
 *   레포에 없다. 규칙 2가 "산포를 다른 축에서 빌리지 않는다"를 명시하므로 FCP의 0.2~0.4%를
 *   가져다 쓸 수도 없다.
 *
 *   **시드만 늘리면 판정 불가한 두 점이 네 점이 될 뿐이다.** 그래서 산포가 선행이다.
 *
 * ★ 교락 경고 ★
 *   009_2 §6.4가 "**Layout은 폰트 몫이 섞여 있었다** — 이 군을 따로 안 쟀으면 'path 합치기가
 *   Layout을 79% 줄였다'로 적을 뻔했다"고 남겼다. Layout 수치는 그 전제를 달고 읽는다.
 *
 * 실행:
 *   E2E_BASE_URL=https://localhost PERF_PERFORMANCE_ID=.. PERF_SCHEDULE_ID=.. PERF_GRADE_ID=.. \
 *     PERF_RENDER_RUNS=6 npx playwright test --project=perf e2e/perf/seatmap-render-spread.spec.ts
 *
 * 렌더 모드는 FE 빌드 시점의 `NEXT_PUBLIC_SEATMAP_SEAT_RENDER`가 정한다(`seat-map.tsx`).
 * 이 스펙은 그 값을 읽지 않고 **렌더된 SVG 구조에서 거꾸로 확인한다** — 016 §7의 교훈 10대로
 * "빌드에 값이 들어갔다"를 믿지 않는다.
 */
import { expect, test } from "@playwright/test";
import { median, printTable, requireBaseUrl, requireSeatMapTarget, round, seatMapPath } from "./target";

const RUNS = Number(process.env.PERF_RENDER_RUNS ?? 6);

interface Sample {
  nodes: number;
  listeners: number;
  heapKb: number;
  layoutMs: number;
  recalcStyleMs: number;
  scriptMs: number;
  rects: number;
  paths: number;
  subpaths: number;
  pathDataBytes: number;
}

function stats(values: number[]): { med: number; min: number; max: number; spreadPct: number } {
  const med = median(values);
  const min = Math.min(...values);
  const max = Math.max(...values);
  return {
    med,
    min,
    max,
    // 축별 산포 표와 같은 정의: (max - min) / 중앙값.
    spreadPct: med === 0 ? Number.NaN : ((max - min) / med) * 100,
  };
}

test.describe("좌석맵 초기 렌더 비용", () => {
  test.describe.configure({ timeout: 30 * 60 * 1000 });

  test("하이드레이션 직후 렌더 비용을 n회 재서 산포를 낸다", async ({ browser }) => {
    const baseUrl = requireBaseUrl();
    const target = requireSeatMapTarget();
    const url = `${baseUrl}${seatMapPath(target)}`;

    // 좌석 총수는 DOM이 아니라 BE 응답에서 가져온다. 정적 좌석을 path로 합치면 DOM에서
    // 좌석을 셀 수 없게 되고, 그러면 "좌석당"의 분모가 렌더 모드에 따라 달라져서
    // 두 모드를 비교할 수 없다 (dom-memory.spec.ts와 같은 이유).
    const probe = await browser.newContext({ ignoreHTTPSErrors: true });
    const probePage = await probe.newPage();
    const apiResponse = await probePage.request.get(
      `${baseUrl}/api/v1/schedules/${target.scheduleId}/seats`
    );
    expect(apiResponse.status(), "좌석 API가 200이 아니다").toBe(200);
    const apiJson = await apiResponse.json();
    const seatArray = apiJson?.data ?? apiJson;
    const seatTotal: number = Array.isArray(seatArray)
      ? seatArray.length
      : (seatArray?.selectable?.length ?? 0) + (seatArray?.others?.length ?? 0);
    await probe.close();
    expect(seatTotal, "좌석 총수를 응답에서 못 읽었다").toBeGreaterThan(100);

    console.log(`\n[렌더 산포] 좌석 ${seatTotal.toLocaleString()}석 / ${RUNS}회`);

    const samples: Sample[] = [];
    for (let run = 1; run <= RUNS; run += 1) {
      // 회차마다 새 context. 누적 지표라 같은 페이지를 다시 쓰면 회차가 섞인다.
      const context = await browser.newContext({ ignoreHTTPSErrors: true });
      const page = await context.newPage();
      try {
        const cdp = await page.context().newCDPSession(page);
        await cdp.send("Performance.enable");

        await page.goto(url, { waitUntil: "networkidle" });
        await page.evaluate(() => document.fonts.ready);

        // 하이드레이션이 끝나기 전에 재면 리스너와 heap이 덜 찬 상태다.
        // 좌석이 반응할 준비가 됐는지 확인한 뒤 측정에 들어간다 (dom-memory.spec.ts와 동일).
        await expect(page.locator('rect[role="checkbox"]').first()).toHaveAttribute(
          "aria-checked",
          "false"
        );

        // GC를 먼저 돌린다. 안 돌리면 수거 대기 중인 쓰레기가 회차 간 편차를 키운다.
        await cdp.send("HeapProfiler.collectGarbage");
        const counters = await cdp.send("Memory.getDOMCounters");
        const perf = await cdp.send("Performance.getMetrics");
        const pick = (name: string) =>
          perf.metrics.find((metric) => metric.name === name)?.value ?? Number.NaN;

        // SVG 구조. **렌더 모드를 거꾸로 확인하는 자리다** — rect 모드면 정적 레이어에
        // path가 0개이고, path 모드면 등급 수만큼(최대 5개) 있다.
        const structure = await page.evaluate(() => {
          const svg = document.querySelector('svg[role="group"]');
          const staticLayer = svg?.querySelector('g[aria-hidden="true"]') ?? null;
          const paths = Array.from(staticLayer?.querySelectorAll("path") ?? []);
          const encoder = new TextEncoder();
          let subpaths = 0;
          let pathDataBytes = 0;
          for (const path of paths) {
            const d = path.getAttribute("d") ?? "";
            subpaths += (d.match(/M/gi) ?? []).length;
            pathDataBytes += encoder.encode(d).length;
          }
          return {
            // 전체 rect: 선택 가능 좌석 + rect 모드의 정적 좌석.
            rects: svg?.querySelectorAll("rect").length ?? 0,
            paths: paths.length,
            subpaths,
            pathDataBytes,
          };
        });

        samples.push({
          nodes: counters.nodes,
          listeners: counters.jsEventListeners,
          heapKb: pick("JSHeapUsedSize") / 1024,
          layoutMs: pick("LayoutDuration") * 1000,
          recalcStyleMs: pick("RecalcStyleDuration") * 1000,
          scriptMs: pick("ScriptDuration") * 1000,
          ...structure,
        });
      } finally {
        await context.close();
      }
      process.stdout.write(`  run ${run}/${RUNS} 완료\n`);
    }

    // --- 렌더 모드 확인 ---
    const mode = samples[0].paths === 0 ? "rect" : "path";
    const staticSeats = mode === "rect" ? samples[0].rects : samples[0].subpaths;
    console.log(
      `[렌더 산포] SVG에서 확인한 모드: ${mode}` +
        ` (path ${samples[0].paths}개 / 서브패스 ${samples[0].subpaths.toLocaleString()}` +
        ` / rect ${samples[0].rects.toLocaleString()})`
    );

    // --- 표 1: 회차별 ---
    printTable(
      `회차별 — ${seatTotal.toLocaleString()}석 / ${mode} 모드`,
      samples.map((sample, index) => ({
        run: index + 1,
        RecalcStyle: round(sample.recalcStyleMs),
        Layout: round(sample.layoutMs),
        Script: round(sample.scriptMs),
        "DOM 노드": sample.nodes.toLocaleString(),
        리스너: sample.listeners.toLocaleString(),
        "heap KB": round(sample.heapKb),
      }))
    );

    // --- 표 2: 산포 ---
    const axes: Array<[string, (sample: Sample) => number]> = [
      ["RecalcStyle ms", (sample) => sample.recalcStyleMs],
      ["Layout ms", (sample) => sample.layoutMs],
      ["Script ms", (sample) => sample.scriptMs],
      ["DOM 노드", (sample) => sample.nodes],
      ["리스너", (sample) => sample.listeners],
      ["heap KB", (sample) => sample.heapKb],
    ];
    printTable(
      `산포 (${RUNS}회) — ${seatTotal.toLocaleString()}석 / ${mode} 모드`,
      axes.map(([label, read]) => {
        const summary = stats(samples.map(read));
        return {
          축: label,
          중앙값: round(summary.med),
          최소: round(summary.min),
          최대: round(summary.max),
          "산포 %": round(summary.spreadPct, 1),
          // 규칙 1: 예상 효과가 산포 x 2보다 작으면 판정이 안 된다.
          "판정 가능 최소 효과 %": round(summary.spreadPct * 2, 1),
          "좌석당": round(summary.med / seatTotal, 4),
        };
      })
    );

    // --- 표 3: SVG 구조 ---
    printTable(`SVG 구조 — ${mode} 모드`, [
      {
        모드: mode,
        "전체 rect": samples[0].rects.toLocaleString(),
        "정적 path": samples[0].paths,
        서브패스: samples[0].subpaths.toLocaleString(),
        "d 바이트": samples[0].pathDataBytes.toLocaleString(),
        "정적 좌석(환산)": staticSeats.toLocaleString(),
      },
    ]);

    // --- 무결성 ---
    // 구조 단정. path 모드면 서브패스 + 선택 가능 rect가 좌석 총수여야 하고,
    // rect 모드면 rect 전체가 좌석 총수(+ 컨테이너 rect 몇 개)여야 한다.
    // dom-memory.spec.ts의 같은 단정을 두 모드로 넓힌 것이다.
    if (mode === "path") {
      const selectable = samples[0].rects;
      expect(
        samples[0].subpaths + selectable,
        "서브패스 + 선택 가능 rect가 좌석 총수와 안 맞는다"
      ).toBeGreaterThanOrEqual(seatTotal);
    } else {
      expect(samples[0].rects, "rect 모드인데 rect가 좌석 총수보다 적다").toBeGreaterThanOrEqual(
        seatTotal
      );
    }
    // 회차 간 구조가 흔들리면 같은 것을 재고 있지 않다.
    for (const sample of samples) {
      expect(sample.paths, "회차마다 path 개수가 다르다").toBe(samples[0].paths);
      expect(sample.rects, "회차마다 rect 개수가 다르다").toBe(samples[0].rects);
    }
  });
});
