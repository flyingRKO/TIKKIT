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
 * 스트리밍 마커별 도착 시각 (Task 030_2).
 *
 * 왜 FCP로 재지 않는가
 * --------------------
 * 좌석맵 FCP는 009_1 측정에서 산포 25.2%로 "판단 불가"다(5회가 491~494ms, 1회가 370ms).
 * 회차를 늘려도 풀리지 않는다 — (max-min)/중앙값 식은 표본 하나로 결정되므로 11회로 늘려도
 * 빠른 모드가 1~2회 나오면 그대로 20%를 넘는다. 009가 3 -> 6회로 늘렸을 때 중앙값이
 * 뒤집힌 것이 그 증거다.
 *
 * 그리고 Task 030_2의 항목 2(좌석 조회를 Suspense로 분리)가 바꾸는 것은 애초에 FCP가 아니다.
 * loading.tsx가 이미 Suspense 경계라서 셸 + 스켈레톤은 지금도 먼저 플러시된다
 * (009 재현 블록의 TTFB 0.006~0.009s). 분리가 당기는 것은 첫 바이트가 아니라
 * "제목·회차·등급 같은 실제 텍스트가 좌석 조회를 안 기다리고 먼저 오는가"다.
 *
 * 그래서 HTML 스트림을 직접 읽어 마커별 도착 시각을 잰다. 브라우저도 Lantern 시뮬레이션도
 * 끼지 않아서 노이즈가 없고, 겨냥하는 변화를 정확히 가리킨다.
 *
 * 읽는 방법
 * --------
 * 마커가 청크 경계에 걸쳐 있으면 놓친다. 디코드한 텍스트를 누적하고, 다음 검색을
 * (직전 검색 위치 - 마커 길이 + 1)에서 시작해 경계를 덮는다.
 *
 * 이 스펙은 수치를 뽑는 용도다. 통과/실패로 성능을 판정하지 않는다(임계값 없음).
 */

const RUNS = 10;

/**
 * 도착 순서대로 기대하는 마커들.
 *
 * - 셸(헤더/스켈레톤/푸터)은 Suspense fallback과 함께 먼저 나간다
 * - 본문 텍스트와 좌석은 page.tsx의 await가 끝나야 나간다. 지금은 셋(detail/grades/seats)이
 *   한 덩어리라 전부 같이 늦고, 항목 2 이후에는 본문이 좌석보다 먼저 와야 한다
 *
 * 주의: React의 out-of-order 스트리밍은 나중에 풀린 내용을 body 끝의 숨은 template에
 * 붙이고 인라인 script로 끼워 넣는다. 즉 "HTML에 그 문자열이 처음 나타난 시각"은
 * 브라우저가 그걸 그린 시각이 아니라 서버가 그걸 내보낼 수 있게 된 시각이다.
 * 우리가 보려는 것이 정확히 그 서버 쪽 시각이라 이 해석이 맞다.
 */
const MARKERS = [
  { key: "헤더 로고(셸)", needle: ">TIKKIT<" },
  { key: "좌석맵 스켈레톤", needle: 'data-slot="skeleton"' },
  { key: "본문 링크", needle: "공연 정보로 돌아가기" },
  { key: "본문 h1", needle: "좌석 선택" },
  { key: "첫 좌석", needle: 'role="checkbox"' },
] as const;

type MarkerKey = (typeof MARKERS)[number]["key"];

interface RunResult {
  /** 첫 바이트까지 (ms) */
  firstByte: number;
  /** 마커별 첫 등장 시각 (ms). 끝까지 안 나오면 null */
  markers: Record<MarkerKey, number | null>;
  /** 스트림 종료까지 (ms) */
  end: number;
  totalBytes: number;
}

async function measureOnce(url: string): Promise<RunResult> {
  const startedAt = performance.now();
  const response = await fetch(url, {
    headers: { "Accept-Encoding": "identity" },
    redirect: "manual",
  });
  if (response.status !== 200) {
    throw new Error(`${url}가 ${response.status}를 줬다. 시드/URL을 확인해라.`);
  }
  if (response.body === null) {
    throw new Error("응답 본문 스트림이 없다.");
  }

  const markers = Object.fromEntries(MARKERS.map((marker) => [marker.key, null])) as Record<
    MarkerKey,
    number | null
  >;
  // 마커별로 "어디까지 검색했는지"를 따로 기억한다. 이미 찾은 마커는 더 안 본다.
  const scanned = Object.fromEntries(MARKERS.map((marker) => [marker.key, 0])) as Record<
    MarkerKey,
    number
  >;

  const decoder = new TextDecoder("utf-8");
  let text = "";
  let totalBytes = 0;
  let firstByte: number | null = null;

  for await (const chunk of response.body as unknown as AsyncIterable<Uint8Array>) {
    const at = performance.now() - startedAt;
    if (firstByte === null) {
      firstByte = at;
    }
    totalBytes += chunk.byteLength;
    // stream: true가 없으면 멀티바이트 문자가 청크 경계에서 깨진다(한글 마커가 안 잡힌다).
    text += decoder.decode(chunk, { stream: true });

    for (const marker of MARKERS) {
      if (markers[marker.key] !== null) {
        continue;
      }
      // 경계에 걸친 마커를 놓치지 않게 마커 길이 - 1 만큼 되감아 검색한다.
      const from = Math.max(0, scanned[marker.key] - marker.needle.length + 1);
      const found = text.indexOf(marker.needle, from);
      if (found === -1) {
        scanned[marker.key] = text.length;
      } else {
        markers[marker.key] = at;
      }
    }
  }
  text += decoder.decode();

  // 마지막 디코드에서 나타난 마커를 줍는다 (스트림 끝에 걸린 경우).
  const end = performance.now() - startedAt;
  for (const marker of MARKERS) {
    if (markers[marker.key] === null && text.includes(marker.needle)) {
      markers[marker.key] = end;
    }
  }

  if (firstByte === null) {
    throw new Error("청크를 하나도 못 받았다.");
  }
  return { firstByte, markers, end, totalBytes };
}

test.describe("좌석맵 스트리밍 마커", () => {
  test("HTML 스트림을 직접 읽어 마커별 도착 시각을 잰다", async () => {
    test.setTimeout(120_000);

    const baseUrl = requireBaseUrl();
    const target = requireSeatMapTarget();
    const url = `${baseUrl}${seatMapPath(target)}`;

    const results: RunResult[] = [];
    for (let index = 0; index < RUNS; index += 1) {
      results.push(await measureOnce(url));
    }

    // 좌석이 실제로 실려 있는지 먼저 본다. 빈 좌석맵을 재면 전부 빨라지고 그게
    // "개선"으로 보이는 사고가 난다 (009 4.9절과 같은 종류).
    const seatMarkerFound = results.filter((run) => run.markers["첫 좌석"] !== null).length;
    expect(
      seatMarkerFound,
      '좌석 마커(role="checkbox")가 안 잡혔다 — 빈 좌석맵을 재고 있을 수 있다'
    ).toBe(results.length);

    const totals = results.map((run) => run.totalBytes);
    expect(
      Math.max(...totals) - Math.min(...totals),
      `회차 간 바이트가 다르다 (최소 ${Math.min(...totals)} / 최대 ${Math.max(...totals)}) — 같은 응답을 재고 있지 않다`
    ).toBeLessThan(Math.min(...totals) * 0.02);

    const rows: Array<Record<string, string | number>> = [];
    const pushRow = (label: string, values: number[]) => {
      const mid = median(values);
      rows.push({
        마커: label,
        중앙값: round(mid),
        최소: round(Math.min(...values)),
        최대: round(Math.max(...values)),
        산포: `${round(mid === 0 ? 0 : ((Math.max(...values) - Math.min(...values)) / mid) * 100, 1)}%`,
      });
    };

    pushRow("(a) 첫 바이트", results.map((run) => run.firstByte));
    for (const marker of MARKERS) {
      const values = results.map((run) => run.markers[marker.key]).filter((v): v is number => v !== null);
      if (values.length === 0) {
        rows.push({ 마커: marker.key, 중앙값: "미등장", 최소: "-", 최대: "-", 산포: "-" });
        continue;
      }
      pushRow(marker.key, values);
    }
    pushRow("(d) 스트림 종료", results.map((run) => run.end));

    printTable(`마커별 도착 시각 (ms, n=${RUNS})`, rows);

    // 항목 2의 판정에 직접 쓰는 값: 본문 텍스트가 스트림 종료에서 얼마나 떨어져 있는가.
    // 0에 가까우면 "본문이 좌석을 기다린다"는 뜻이고, 음수로 벌어지면 분리된 것이다.
    const gaps = results
      .filter((run) => run.markers["본문 h1"] !== null)
      .map((run) => ({
        bodyFromFirstByte: run.markers["본문 h1"]! - run.firstByte,
        endMinusBody: run.end - run.markers["본문 h1"]!,
        seatMinusBody:
          run.markers["첫 좌석"] === null ? Number.NaN : run.markers["첫 좌석"]! - run.markers["본문 h1"]!,
      }));

    printTable(`항목 2 판정용 간격 (ms, n=${gaps.length})`, [
      {
        간격: "본문 h1 - 첫 바이트",
        중앙값: round(median(gaps.map((g) => g.bodyFromFirstByte))),
        해석: "셸이 나간 뒤 본문이 오기까지. 작아지면 분리 성공",
      },
      {
        간격: "스트림 종료 - 본문 h1",
        중앙값: round(median(gaps.map((g) => g.endMinusBody))),
        해석: "본문 뒤에 남은 꼬리. 커지면 좌석이 본문과 분리된 것",
      },
      {
        간격: "첫 좌석 - 본문 h1",
        중앙값: round(median(gaps.map((g) => g.seatMinusBody))),
        해석: "0이면 한 덩어리로 온다는 뜻",
      },
    ]);

    printTable("측정 대상", [
      {
        URL: seatMapPath(target),
        "응답 바이트": results[0].totalBytes.toLocaleString(),
        회차: RUNS,
        비고: "Accept-Encoding: identity (압축 해제 시간을 섞지 않는다)",
      },
    ]);
  });
});
