import { expect, test } from "@playwright/test";
import { printTable, requireBaseUrl, requireSeatMapTarget, round, seatMapPath } from "./target";

/**
 * 좌석맵의 DOM 노드 수와 JS heap (Task 030_2).
 *
 * 왜 이걸 재는가
 * -------------
 * ROADMAP이 Task 030_2의 근거를 "전송량이 아니라 확장성"으로 못박았다. 좌석맵 문서 전송이
 * 32.9KB뿐이라 반으로 줄여도 16KB이고, 그 숫자로는 구조 변경을 정당화할 수 없다.
 *
 * 그리고 정적 좌석을 등급별 <path>로 합치는 변경(항목 3-d)은 애초에 바이트 레버가 아니다 —
 * <rect x=".." y=".." width="8" height="8" rx="1.5" class=".."/>가 약 85B인데 같은 좌석을
 * 둥근 모서리까지 보존한 path 명령으로 쓰면 80~105B라서 바이트는 거의 그대로다.
 * 실제로 줄어드는 것은 DOM 노드 수(SVG 요소 하나가 브라우저 메모리의 실객체다)와
 * React가 클릭마다 재조정하는 vdom 수다. 그래서 그 둘을 직접 잰다.
 *
 * 좌석 수로 나눠서 보는 이유: 두 군(2,000석 / 10,000석)의 "좌석당 비용"을 비교해야
 * 기울기가 줄었는지 말할 수 있다. 한 점에서는 기울기를 말할 수 없다.
 *
 * 이 스펙은 수치를 뽑는 용도다. 통과/실패로 성능을 판정하지 않는다(임계값 없음).
 */

interface CdpMetrics {
  nodes: number;
  listeners: number;
  documents: number;
  heapUsed: number;
  layoutMs: number;
  recalcStyleMs: number;
  scriptMs: number;
}

test.describe("좌석맵 DOM·메모리", () => {
  test("DOM 노드 수와 JS heap을 좌석당으로 환산한다", async ({ page }) => {
    test.setTimeout(180_000);

    const baseUrl = requireBaseUrl();
    const target = requireSeatMapTarget();

    // 좌석 총수는 DOM이 아니라 BE 응답에서 가져온다. 정적 좌석을 path로 합치면 DOM에서
    // 좌석을 셀 수 없게 되는데, 그러면 "좌석당"의 분모가 변경에 따라 달라져서
    // before/after를 비교할 수 없다.
    const apiResponse = await page.request.get(
      `${baseUrl}/api/v1/schedules/${target.scheduleId}/seats`
    );
    expect(apiResponse.status(), "좌석 API가 200이 아니다").toBe(200);
    const apiBody = await apiResponse.body();
    const payload = JSON.parse(apiBody.toString("utf8"));
    // ApiResponse 래퍼({ data: [...] })와 배열 직응답을 모두 받아준다 — 항목 3-b에서
    // 응답 구조가 바뀔 수 있어서 여기서 분모가 깨지지 않게 둔다.
    const seatArray = Array.isArray(payload) ? payload : payload.data;
    const seatTotal = Array.isArray(seatArray)
      ? seatArray.length
      : // 항목 3-b의 축약 구조({ selectable, others })면 둘을 합친다
        (seatArray?.selectable?.length ?? 0) + (seatArray?.others?.length ?? 0);
    expect(seatTotal, "좌석 총수를 응답에서 못 읽었다 — 구조가 바뀌었으면 이 스펙도 고쳐라").toBeGreaterThan(100);

    const cdp = await page.context().newCDPSession(page);
    await cdp.send("Performance.enable");

    await page.goto(`${baseUrl}${seatMapPath(target)}`, { waitUntil: "networkidle" });
    await page.evaluate(() => document.fonts.ready);

    // 하이드레이션이 끝나기 전에 재면 이벤트 리스너와 heap이 덜 찬 상태다.
    // 좌석이 실제로 반응할 준비가 됐는지 확인한 뒤 측정에 들어간다.
    const firstSeat = page.locator('rect[role="checkbox"]').first();
    await expect(firstSeat).toHaveAttribute("aria-checked", "false");

    async function readMetrics(): Promise<CdpMetrics> {
      // GC를 먼저 돌린다. 안 돌리면 heap에 수거 대기 중인 쓰레기가 섞여서 회차 간
      // 편차가 측정하려는 차이보다 커진다.
      await cdp.send("HeapProfiler.collectGarbage");
      const counters = await cdp.send("Memory.getDOMCounters");
      const perf = await cdp.send("Performance.getMetrics");
      const pick = (name: string) =>
        perf.metrics.find((metric) => metric.name === name)?.value ?? Number.NaN;
      return {
        nodes: counters.nodes,
        listeners: counters.jsEventListeners,
        documents: counters.documents,
        heapUsed: pick("JSHeapUsedSize"),
        // 누적 초 단위로 온다. ms로 바꾼다.
        layoutMs: pick("LayoutDuration") * 1000,
        recalcStyleMs: pick("RecalcStyleDuration") * 1000,
        scriptMs: pick("ScriptDuration") * 1000,
      };
    }

    const idle = await readMetrics();

    // SVG 구조 분해. 항목 3-d가 rect를 path로 합치는 변경이라 before/after에서 이 표의
    // 모양 자체가 바뀐다. path의 서브패스 수('M' 명령 개수)로 합쳐진 좌석 수를 센다.
    const structure = await page.evaluate(() => {
      const svg = document.querySelector('svg[role="group"]');
      const staticLayer = svg?.querySelector('g[aria-hidden="true"]') ?? null;
      const paths = Array.from(staticLayer?.querySelectorAll("path") ?? []);
      let subpaths = 0;
      let pathDataBytes = 0;
      const encoder = new TextEncoder();
      for (const path of paths) {
        const d = path.getAttribute("d") ?? "";
        pathDataBytes += encoder.encode(d).length;
        subpaths += (d.match(/M/gi) ?? []).length;
      }
      return {
        svgTotalElements: svg?.querySelectorAll("*").length ?? 0,
        rectTotal: document.querySelectorAll("rect").length,
        rectSelectable: document.querySelectorAll('rect[role="checkbox"]').length,
        rectStatic: staticLayer?.querySelectorAll("rect").length ?? 0,
        pathCount: paths.length,
        pathSubpaths: subpaths,
        pathDataBytes,
      };
    });

    // 선택 가능 좌석을 매수 상한만큼 눌러서 선택 상태의 비용을 본다.
    // 클릭 응답 시간은 seat-interaction.spec.ts가 재고, 여기서는 할당량만 본다.
    for (let index = 0; index < target.quantity; index += 1) {
      await page.locator('rect[role="checkbox"]').nth(index).click();
    }
    await expect(page.locator('rect[role="checkbox"]').first()).toHaveAttribute(
      "aria-checked",
      "true"
    );
    const selected = await readMetrics();

    // 선택 표시 레이어는 선택이 있어야 생긴다. 위 구조 분해는 클릭 전에 재기 때문에
    // 선택 상태에서만 보이는 rect는 여기서 따로 센다 (Task 035).
    //
    // rect 전체를 다시 세는 이유: 선택 표시를 별도 오버레이로 빼면 그 rect가
    // document.querySelectorAll("rect")에 섞여 들어온다. 어느 몫이 오버레이인지 가려두지
    // 않으면 "rect가 늘었다"가 회귀로 읽힌다.
    const selectedStructure = await page.evaluate(() => {
      const svg = document.querySelector('svg[role="group"]');
      const overlay = svg?.querySelector('[data-layer="selection-overlay"]') ?? null;
      return {
        rectTotal: document.querySelectorAll("rect").length,
        rectOverlay: overlay?.querySelectorAll("rect").length ?? 0,
      };
    });

    const perSeat = (value: number) => round(value / seatTotal, 3);

    printTable("DOM·메모리 (좌석 " + seatTotal.toLocaleString() + "석)", [
      {
        상태: "하이드레이션 직후",
        "DOM 노드": idle.nodes.toLocaleString(),
        "노드/좌석": perSeat(idle.nodes),
        "heap KB": round(idle.heapUsed / 1024),
        "heap B/좌석": round(idle.heapUsed / seatTotal, 1),
        리스너: idle.listeners.toLocaleString(),
      },
      {
        상태: `좌석 ${target.quantity}매 선택 후`,
        "DOM 노드": selected.nodes.toLocaleString(),
        "노드/좌석": perSeat(selected.nodes),
        "heap KB": round(selected.heapUsed / 1024),
        "heap B/좌석": round(selected.heapUsed / seatTotal, 1),
        리스너: selected.listeners.toLocaleString(),
      },
    ]);

    printTable("누적 렌더 비용 (ms, 페이지 생애 누적)", [
      {
        상태: "하이드레이션 직후",
        Layout: round(idle.layoutMs),
        RecalcStyle: round(idle.recalcStyleMs),
        Script: round(idle.scriptMs),
      },
      {
        상태: `좌석 ${target.quantity}매 선택 후`,
        Layout: round(selected.layoutMs),
        RecalcStyle: round(selected.recalcStyleMs),
        Script: round(selected.scriptMs),
      },
      {
        상태: "└ 선택으로 늘어난 몫",
        Layout: round(selected.layoutMs - idle.layoutMs),
        RecalcStyle: round(selected.recalcStyleMs - idle.recalcStyleMs),
        Script: round(selected.scriptMs - idle.scriptMs),
      },
    ]);

    printTable("SVG 구조 분해", [
      { 항목: "SVG 하위 요소 전체", 개수: structure.svgTotalElements.toLocaleString(), 비고: "" },
      {
        항목: "rect 전체",
        개수: structure.rectTotal.toLocaleString(),
        비고: `선택 가능 ${structure.rectSelectable.toLocaleString()} / 정적 ${structure.rectStatic.toLocaleString()}`,
      },
      {
        항목: "정적 레이어 path",
        개수: structure.pathCount.toLocaleString(),
        비고:
          structure.pathCount === 0
            ? "아직 rect로 그린다 (항목 3-d 전)"
            : `서브패스 ${structure.pathSubpaths.toLocaleString()}개 / d 속성 ${structure.pathDataBytes.toLocaleString()}B`,
      },
      {
        항목: "정적 좌석 귀속 합계",
        개수: (structure.rectStatic + structure.pathSubpaths).toLocaleString(),
        비고: `좌석 총수 ${seatTotal.toLocaleString()} - 선택 가능 ${structure.rectSelectable.toLocaleString()} = ${(seatTotal - structure.rectSelectable).toLocaleString()}과 맞아야 한다`,
      },
      {
        항목: `선택 표시 rect (${target.quantity}매 선택 후)`,
        개수: selectedStructure.rectOverlay.toLocaleString(),
        비고:
          selectedStructure.rectOverlay === 0
            ? "별도 레이어 없음 — 좌석 rect 자체가 선택 표시를 들고 있다"
            : `rect 전체 ${structure.rectTotal.toLocaleString()} → ${selectedStructure.rectTotal.toLocaleString()} 중 이 몫이 오버레이다`,
      },
    ]);

    // 정적 좌석이 rect든 path 서브패스든, 둘을 합치면 "선택 불가 좌석 수"와 맞아야 한다.
    // 안 맞으면 좌석이 통째로 빠진 상태를 재고 있다는 뜻이다 (009 4.9절과 같은 종류의 사고).
    expect(
      structure.rectStatic + structure.pathSubpaths,
      "정적 좌석 수가 안 맞는다 — 좌석이 빠진 상태를 재고 있을 수 있다"
    ).toBe(seatTotal - structure.rectSelectable);
  });
});
