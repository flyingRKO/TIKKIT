import { expect, test, type Response } from "@playwright/test";
import { printTable, requireBaseUrl, requireSeatMapTarget, round, seatMapPath } from "./target";

/**
 * 좌석맵 페이지의 전송량 분해 (Task 030 측정 단계).
 *
 * 재는 이유: 006이 "좌석맵 응답 237.9KB / 2,000석"을 BE 기준으로 남겼는데, FE에서는 같은
 * 좌석 배열이 한 번 더 실린다. Server Component가 받은 seats를 SeatSelector(Client
 * Component)로 넘기기 때문에 RSC 페이로드에 직렬화되어 브라우저로 또 내려간다
 * (app/(main)/performances/[id]/schedules/[scheduleId]/seats/page.tsx).
 *
 * 그래서 "BE 응답을 줄이면 FE도 줄어든다"가 성립하지 않는다. 두 구간을 따로 잰다.
 *
 *   층 ②  BE -> Next 서버      : /api/v1/schedules/{id}/seats 응답
 *   층 ③  Next -> 브라우저      : 문서 HTML 안의 인라인 RSC + JS 번들
 *
 * 층 ①(DB+조립)은 BE의 tikkit.seatmap.build Timer로 따로 본다 — 여기서는 재지 않는다.
 */

/** 첫 네비게이션에서 RSC 플라이트 페이로드는 별도 요청이 아니라 이 마커로 HTML에 인라인된다. */
const RSC_INLINE_MARKER = "self.__next_f.push";

interface ResponseRow {
  resourceType: string;
  count: number;
  transferBytes: number;
  decodedBytes: number;
}

test.describe("좌석맵 전송량", () => {
  test("직접 진입 — 문서 HTML과 인라인 RSC를 분해한다", async ({ page }) => {
    const baseUrl = requireBaseUrl();
    const target = requireSeatMapTarget();
    const url = `${baseUrl}${seatMapPath(target)}`;

    const responses: Response[] = [];
    page.on("response", (response) => responses.push(response));

    const documentResponse = await page.goto(url, { waitUntil: "networkidle" });
    expect(documentResponse, "문서 응답이 없다").not.toBeNull();
    expect(documentResponse!.status(), `좌석맵 페이지가 ${documentResponse!.status()}를 줬다`).toBe(200);

    // 좌석이 실제로 그려졌는지 먼저 확인한다. 빈 좌석맵을 재면 바이트가 작게 나오고
    // 그게 "개선"으로 보이는 사고가 난다.
    //
    // rect 개수만 세면 안 된다 — Task 030_2가 정적 좌석을 등급별 <path>로 합치면서 rect 수가
    // "선택 가능 좌석 수"로 떨어진다. 그러면 이 검사가 개선 자체를 오염으로 오판한다.
    // path의 서브패스('M' 명령) 수를 같이 세서 "그려진 좌석 총수"로 판정한다.
    const seatCounts = await page.evaluate(() => {
      const staticLayer = document.querySelector('svg[role="group"] g[aria-hidden="true"]');
      let pathSubpaths = 0;
      for (const path of Array.from(staticLayer?.querySelectorAll("path") ?? [])) {
        pathSubpaths += ((path.getAttribute("d") ?? "").match(/M/gi) ?? []).length;
      }
      const rectTotal = document.querySelectorAll("rect").length;
      const selectable = document.querySelectorAll('rect[role="checkbox"]').length;
      return { rectTotal, selectable, pathSubpaths, drawn: rectTotal + pathSubpaths };
    });
    expect(seatCounts.drawn, "그려진 좌석이 없다 — 시드나 URL을 확인해라").toBeGreaterThan(100);

    // 리소스 타입별 전송/디코드 바이트. 둘의 차이가 압축 효과다.
    const byType = new Map<string, ResponseRow>();
    for (const response of responses) {
      const resourceType = response.request().resourceType();
      let decodedBytes = 0;
      let transferBytes = 0;
      try {
        decodedBytes = (await response.body()).length;
        transferBytes = (await response.request().sizes()).responseBodySize;
      } catch {
        // 리다이렉트처럼 바디를 못 읽는 응답은 건너뛴다
        continue;
      }
      const row = byType.get(resourceType) ?? {
        resourceType,
        count: 0,
        transferBytes: 0,
        decodedBytes: 0,
      };
      row.count += 1;
      row.transferBytes += transferBytes;
      row.decodedBytes += decodedBytes;
      byType.set(resourceType, row);
    }

    const rows = [...byType.values()].sort((a, b) => b.decodedBytes - a.decodedBytes);
    printTable(
      "층 ③ Next -> 브라우저: 리소스 타입별",
      rows.map((row) => ({
        타입: row.resourceType,
        개수: row.count,
        "전송 KB": round(row.transferBytes / 1024),
        "디코드 KB": round(row.decodedBytes / 1024),
        압축: row.transferBytes < row.decodedBytes ? "O" : "X",
      }))
    );

    // 문서 HTML 분해. 인라인 RSC는 별도 요청이 아니라 <script> 안에 들어 있어서
    // page.on("response")로는 "문서 1개 N KB"로 뭉개진다.
    const html = await documentResponse!.text();
    const htmlBytes = Buffer.byteLength(html, "utf8");
    const inline = await page.evaluate((marker) => {
      const encoder = new TextEncoder();
      let rscBytes = 0;
      let rscScripts = 0;
      let otherScriptBytes = 0;
      for (const script of Array.from(document.querySelectorAll("script"))) {
        const text = script.textContent ?? "";
        if (text === "") {
          continue;
        }
        if (text.includes(marker)) {
          rscBytes += encoder.encode(text).length;
          rscScripts += 1;
        } else {
          otherScriptBytes += encoder.encode(text).length;
        }
      }
      return { rscBytes, rscScripts, otherScriptBytes };
    }, RSC_INLINE_MARKER);

    // 마커가 안 잡히면 Next 16이 다른 방식으로 인라인한다는 뜻이다. 조용히 0으로
    // 보고하면 "RSC 페이로드가 없다"는 틀린 결론이 나오므로 여기서 멈춘다.
    expect(
      inline.rscScripts,
      `HTML에서 "${RSC_INLINE_MARKER}" 마커를 못 찾았다. Next 16의 인라인 방식이 다를 수 있다 — HTML을 직접 열어 확인해라`
    ).toBeGreaterThan(0);

    printTable("문서 HTML 분해", [
      { 항목: "HTML 전체", KB: round(htmlBytes / 1024), 비고: "" },
      {
        항목: "└ 인라인 RSC",
        KB: round(inline.rscBytes / 1024),
        비고: `script ${inline.rscScripts}개 / 전체의 ${round((inline.rscBytes / htmlBytes) * 100, 1)}%`,
      },
      { 항목: "└ 그 외 인라인 script", KB: round(inline.otherScriptBytes / 1024), 비고: "" },
      {
        항목: "└ SSR 마크업 등 나머지",
        KB: round((htmlBytes - inline.rscBytes - inline.otherScriptBytes) / 1024),
        비고: `그린 좌석 ${seatCounts.drawn}개(rect ${seatCounts.rectTotal} / path 서브패스 ${seatCounts.pathSubpaths} / 선택 가능 ${seatCounts.selectable})`,
      },
    ]);
  });

  test("층 ② BE 응답과 순수 RSC 페이로드를 각각 잰다", async ({ page }) => {
    const baseUrl = requireBaseUrl();
    const target = requireSeatMapTarget();

    // --- 층 ②: BE가 Next에게 주는 좌석 JSON ---
    // nginx가 /api/를 backend로 보내므로 브라우저 쪽에서도 같은 응답을 받을 수 있다.
    // Accept-Encoding을 두 번 다르게 줘서 압축 적용 여부를 확정한다
    // (nginx.conf에 gzip 지시자가 없어서 비압축일 것으로 보지만 추측으로 두지 않는다).
    const apiUrl = `${baseUrl}/api/v1/schedules/${target.scheduleId}/seats`;
    const apiRows = [];
    for (const acceptEncoding of ["gzip, deflate, br", "identity"]) {
      const response = await page.request.get(apiUrl, {
        headers: { "Accept-Encoding": acceptEncoding },
      });
      expect(response.status(), `${apiUrl}가 ${response.status()}를 줬다`).toBe(200);
      const body = await response.body();
      const headers = response.headers();
      apiRows.push({
        "Accept-Encoding": acceptEncoding,
        "본문 KB": round(body.length / 1024),
        "content-encoding": headers["content-encoding"] ?? "(없음)",
        "content-length": headers["content-length"] ?? "(없음)",
      });
    }
    printTable("층 ② BE -> Next: /schedules/{id}/seats", apiRows);

    // --- 순수 RSC 페이로드 ---
    // 첫 네비게이션에서는 HTML에 인라인되지만, rsc 헤더를 붙이면 플라이트 페이로드만
    // 따로 받는다(Next 16 cdn-caching 문서: "whether the request should return a RSC
    // payload instead of HTML").
    //
    // next-router-state-tree를 같이 보내지 않으므로 서버가 세그먼트 단위 부분 갱신이
    // 아니라 전체 페이로드를 준다 — 같은 문서가 명시한 동작이다. 즉 아래 수치는
    // "실제 링크 클릭이 받는 양"이 아니라 그 상한이다.
    const rscResponse = await page.request.get(`${baseUrl}${seatMapPath(target)}`, {
      headers: { RSC: "1" },
    });
    expect(rscResponse.status()).toBe(200);
    const rscBody = await rscResponse.body();

    const htmlResponse = await page.request.get(`${baseUrl}${seatMapPath(target)}`);
    const htmlBody = await htmlResponse.body();

    printTable("진입 경로별 페이로드", [
      {
        경로: "직접 진입 (HTML)",
        KB: round(htmlBody.length / 1024),
        비고: "인라인 RSC 포함",
      },
      {
        경로: "rsc 헤더 (플라이트 전체)",
        KB: round(rscBody.length / 1024),
        비고: "state-tree 없음 = 전체 페이로드. 실제 링크 클릭의 상한",
      },
    ]);
  });
});
