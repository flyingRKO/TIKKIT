/**
 * 재방문자 비용 측정 (Task 041).
 *
 * ★ 왜 이 측정이 필요한가 ★
 *   009_1이 폰트를 dynamic subset으로 바꿔 폰트 전송을 85.9%(2,057,991 -> 289,305B) 줄였는데,
 *   그 대가로 요청이 1개 -> 11개가 됐다(홈 기준. 상세·좌석맵은 8개). 그 문서가 직접 한계로
 *   남겼다 — "오히려 요청이 1개 -> 11개로 늘어 재방문 시 조건부 요청이 많아지는데 재지 않았다".
 *   009·009_2·016이 같은 한계를 반복해서 적었고, 이유는 전부 하나다: **Lighthouse는 항상
 *   브라우저 캐시 콜드다.**
 *
 * ★ 왜 Lighthouse로 못 재는가 ★
 *   `--disable-storage-reset`을 줘도 안 된다. Lighthouse CLI는 회차마다 **새 Chrome 프로필**로
 *   띄우기 때문에(016 3.2절에 기록돼 있다 — `ssl_session_cache`를 일부러 안 켠 이유가 같다)
 *   디스크 캐시가 애초에 비어 있다. "캐시 콜드"가 그 도구의 전제다.
 *
 * ★ 질문이 039_1 이후로 바뀌었다 ★
 *   HTTP/2가 조각 11개의 커넥션 경쟁을 없애서(홈 FCP -30.1%) 조각 수를 줄일 이유가 사라졌고,
 *   단일 서브셋은 h2 위에서 +16.6%로 역전돼 영구 기각됐다(016 4.4절). 그래서 이 측정의 질문은
 *   "11개를 줄일까"가 아니라 **"11개를 그대로 두고 재방문 시 그 11번이 얼마인가"** 다.
 *
 * ★ 측정 계층을 CDP로 내린 이유 ★
 *   Playwright의 `page.on("response")`로는 캐시 히트를 판정할 수 없다 — 디스크 캐시에서 온
 *   응답도 `status() === 200`으로 똑같이 보인다. 네 가지를 갈라야 질문에 답이 된다:
 *     memory  : `Network.requestServedFromCache` (네트워크 왕복 0)
 *     disk    : `response.fromDiskCache` (네트워크 왕복 0)
 *     304     : 조건부 요청이 실제로 나갔고 본문만 안 받았다  <- 009_1이 걱정한 것
 *     network : 전체를 다시 받았다 (캐시가 아예 안 걸린 것)
 *
 * ★ 스로틀을 거는 이유 — 첫 스모크에서 드러났다 ★
 *   무스로틀로 재니 FCP가 32~44ms로 나왔다. **그 조건에서는 재방문 이득이 구조적으로 측정되지
 *   않는다** — 조건부 요청의 비용은 RTT에 비례하는데 루프백 RTT는 ~0이다. 016 7절이 같은 이유로
 *   "루프백 무스로틀에서는 세 군(평문/TLS/h2)이 전부 판단 불가"를 적었고, README의 축별 산포 표도
 *   그 축을 32~55%로 올려 뒀다. 그래서 `Network.emulateNetworkConditions`로 RTT를 넣는다.
 *
 *   ⚠️ 한계: Lighthouse의 Lantern은 **관측 후 시뮬레이션**이고 이건 **실제 적용 스로틀**이라
 *   두 수치를 직접 비교하면 안 된다. 같은 RTT·대역폭 값을 쓰는 것은 "같은 자로 쟀다"가 아니라
 *   "같은 조건을 의도했다"까지다. 이 측정 안의 1차 vs 2차 비교에만 쓴다.
 */
import { test, expect, type CDPSession, type Page } from "@playwright/test";
import {
  median,
  printTable,
  requireBaseUrl,
  requireSeatMapTarget,
  round,
  seatMapPath,
  type SeatMapTarget,
} from "./target";

/**
 * 회차 수. 방문이 회차당 2번이고 경로가 3개라 기본 6으로 둔다.
 *
 * 010·016이 쓴 6회와 같은 값이다. 축별 산포 표에 올릴 새 축이라 n=1은 애초에 선택지가 아니다
 * (009의 모바일 n=1이 041의 항목으로 돌아온 것이 그 대가다).
 */
const RUNS = Number(process.env.PERF_REVISIT_RUNS ?? 6);

/**
 * 네트워크·CPU 조건. Lighthouse 프리셋의 값과 맞춰 둔다(비교가 아니라 조건을 맞추려는 것이다).
 *
 * `none`은 진단용으로만 남긴다 — 그 조건에서는 위 주석대로 재방문 이득이 0으로 보인다.
 */
const PROFILES = {
  none: null,
  desktop: { label: "desktop(RTT 40ms / 10,240kbps / CPU ×1)", latency: 40, kbps: 10240, cpu: 1 },
  mobile: { label: "mobile(RTT 150ms / 1,638.4kbps / CPU ×4)", latency: 150, kbps: 1638.4, cpu: 4 },
} as const;

type ProfileName = keyof typeof PROFILES;

const PROFILE_NAME = (process.env.PERF_REVISIT_PROFILE ?? "desktop") as ProfileName;
if (!(PROFILE_NAME in PROFILES)) {
  throw new Error(
    `PERF_REVISIT_PROFILE은 ${Object.keys(PROFILES).join(" | ")} 중 하나여야 한다. 받은 값: ${PROFILE_NAME}`
  );
}

/** 폰트 조각을 식별한다. pretendard dynamic-subset은 woff2 조각으로 내려온다. */
const FONT_PATTERN = /\.woff2(\?|$)/i;

type CacheKind = "memory" | "disk" | "304" | "network";

interface NetworkEntry {
  url: string;
  resourceType: string;
  status: number;
  protocol: string;
  cache: CacheKind;
  /** 네트워크로 실제 오간 바이트. 캐시 히트는 0으로 둔다(아래 주석 참조). */
  encodedBytes: number;
  cacheControl: string;
  /** 어느 방문에서 나온 요청인지. 늦게 도착하는 이벤트를 올바른 방문에 귀속시킨다. */
  generation: number;
}

interface VisitResult {
  fcp: number;
  lcp: number;
  entries: NetworkEntry[];
}

/** CDP 이벤트 payload. Playwright의 CDPSession은 타입이 느슨해서 필요한 필드만 좁혀 쓴다. */
interface RequestWillBeSentEvent {
  requestId: string;
  request: { url: string };
  type?: string;
}
interface RequestServedFromCacheEvent {
  requestId: string;
}
interface ResponseReceivedEvent {
  requestId: string;
  type?: string;
  response: {
    url: string;
    status: number;
    protocol?: string;
    fromDiskCache?: boolean;
    encodedDataLength?: number;
    headers: Record<string, string>;
  };
}
interface LoadingFinishedEvent {
  requestId: string;
  encodedDataLength?: number;
}

interface Collector {
  reset(): void;
  drain(): NetworkEntry[];
}

/**
 * CDP 네트워크 수집기.
 *
 * ★ requestId로 매칭한다 ★
 *   처음에는 url + "바이트가 0인 엔트리"로 `loadingFinished`를 붙였는데 **전부 실패했다** —
 *   `responseReceived` 시점에 이미 헤더 바이트(요청당 약 172B)가 들어가 있어서 0이 아니었다.
 *   그래서 홈 1차 방문의 폰트 11개가 1,891B(= 11 × 172)로 찍혔고, 2차 방문은 288,384B로 나왔다.
 *   **캐시 히트가 네트워크보다 바이트를 더 쓰는 표**가 나온 것이고, 그대로 믿었으면
 *   "재방문이 더 비싸다"는 거꾸로 된 결론을 적었을 것이다 (교훈 1의 반대 방향 — 요청이 일을
 *   안 한 게 아니라 **집계가 일을 안 했다**).
 *
 * ★ 캐시 히트의 바이트를 0으로 덮는다 ★
 *   memory/disk 히트의 `encodedDataLength`에는 CDP가 **원본 응답의 전체 크기**를 넣어 준다.
 *   그게 288,384B의 정체다. "네트워크로 오간 바이트"를 재는 열에 그 값이 들어가면 캐시 이득이
 *   손해로 보이므로 0으로 둔다. 원본 크기는 1차 방문 행에 이미 있다.
 */
async function createCollector(client: CDPSession): Promise<Collector> {
  await client.send("Network.enable");

  let generation = 0;
  /** requestId -> 엔트리. reset 후에도 유지해서 늦게 온 이벤트가 제 방문을 찾아간다. */
  const entries = new Map<string, NetworkEntry>();
  const pending = new Map<string, { url: string; resourceType: string; generation: number }>();
  const servedFromCache = new Set<string>();

  client.on("Network.requestWillBeSent", (event: RequestWillBeSentEvent) => {
    pending.set(event.requestId, {
      url: event.request.url,
      resourceType: event.type ?? "(없음)",
      generation,
    });
  });

  // memory cache 히트는 이 이벤트로만 알 수 있다. responseReceived의 fromDiskCache는 false다.
  client.on("Network.requestServedFromCache", (event: RequestServedFromCacheEvent) => {
    servedFromCache.add(event.requestId);
  });

  client.on("Network.responseReceived", (event: ResponseReceivedEvent) => {
    const { response } = event;
    const cache: CacheKind = servedFromCache.has(event.requestId)
      ? "memory"
      : response.fromDiskCache === true
        ? "disk"
        : response.status === 304
          ? "304"
          : "network";
    const cached = cache === "memory" || cache === "disk";
    entries.set(event.requestId, {
      url: response.url,
      resourceType: event.type ?? pending.get(event.requestId)?.resourceType ?? "(없음)",
      status: response.status,
      protocol: response.protocol ?? "(없음)",
      cache,
      encodedBytes: cached ? 0 : (response.encodedDataLength ?? 0),
      cacheControl:
        response.headers["cache-control"] ?? response.headers["Cache-Control"] ?? "(없음)",
      generation: pending.get(event.requestId)?.generation ?? generation,
    });
  });

  // 전송 바이트는 loadingFinished가 정확하다. responseReceived 시점에는 헤더뿐이다.
  client.on("Network.loadingFinished", (event: LoadingFinishedEvent) => {
    const entry = entries.get(event.requestId);
    if (entry === undefined || event.encodedDataLength === undefined) return;
    if (entry.cache === "memory" || entry.cache === "disk") return; // 캐시 히트는 0으로 둔다
    entry.encodedBytes = event.encodedDataLength;
  });

  return {
    reset(): void {
      generation += 1;
    },
    drain(): NetworkEntry[] {
      return [...entries.values()].filter(
        (entry) => entry.generation === generation && /^https?:/.test(entry.url)
      );
    },
  };
}

/** 네트워크·CPU 스로틀을 적용한다. `none`이면 아무것도 걸지 않는다. */
async function applyProfile(client: CDPSession, name: ProfileName): Promise<void> {
  const profile = PROFILES[name];
  if (profile === null) return;
  await client.send("Network.emulateNetworkConditions", {
    offline: false,
    latency: profile.latency,
    // CDP는 bytes/s를 받는다. kbps(비트)를 바이트로 환산한다.
    downloadThroughput: (profile.kbps * 1024) / 8,
    uploadThroughput: (profile.kbps * 1024) / 8,
  });
  await client.send("Emulation.setCPUThrottlingRate", { rate: profile.cpu });
}

/**
 * 페인트 지표를 페이지 안에서 읽는다.
 *
 * Playwright의 WS 왕복을 재는 자가 되지 않게 브라우저 안에서 끝낸다 —
 * `seat-interaction.spec.ts`가 쓰는 방식 그대로다. LCP는 `buffered: true`로 과거 엔트리까지
 * 받고, 마지막 값이 확정값이다.
 */
async function readPaint(page: Page): Promise<{ fcp: number; lcp: number }> {
  return page.evaluate(
    () =>
      new Promise<{ fcp: number; lcp: number }>((resolve) => {
        const paint = performance.getEntriesByName("first-contentful-paint")[0];
        const fcp = paint === undefined ? Number.NaN : paint.startTime;
        let lcp = Number.NaN;
        const observer = new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) lcp = entry.startTime;
        });
        try {
          observer.observe({ type: "largest-contentful-paint", buffered: true });
        } catch {
          // LCP를 지원하지 않는 환경은 NaN으로 둔다. 측정을 멈추지는 않는다.
        }
        // LCP는 네트워크가 멎은 뒤에도 갱신될 수 있어서 짧게 기다린다.
        setTimeout(() => {
          observer.disconnect();
          resolve({ fcp, lcp });
        }, 300);
      })
  );
}

async function visit(page: Page, url: string, collector: Collector): Promise<VisitResult> {
  collector.reset();
  const response = await page.goto(url, { waitUntil: "networkidle" });
  expect(response, `${url} 응답이 없다`).not.toBeNull();
  expect(response!.status(), `${url}가 ${response!.status()}를 줬다`).toBe(200);
  const paint = await readPaint(page);
  return { ...paint, entries: collector.drain() };
}

/** 캐시 종류별 요청 수를 "memory 11 / disk 2" 같은 한 줄로 만든다. */
function cacheSummary(entries: NetworkEntry[]): string {
  const counts = new Map<CacheKind, number>();
  for (const entry of entries) counts.set(entry.cache, (counts.get(entry.cache) ?? 0) + 1);
  const order: CacheKind[] = ["memory", "disk", "304", "network"];
  const summary = order
    .filter((kind) => counts.has(kind))
    .map((kind) => `${kind} ${counts.get(kind)}`)
    .join(" / ");
  return summary === "" ? "(요청 없음)" : summary;
}

function spread(values: number[]): number {
  const clean = values.filter((value) => Number.isFinite(value));
  if (clean.length === 0) return Number.NaN;
  const mid = median(clean);
  return mid === 0 ? Number.NaN : (Math.max(...clean) - Math.min(...clean)) / mid;
}

test.describe("재방문자 비용", () => {
  // 스로틀을 걸면 한 방문이 수 초가 되고, 경로 3개 x 2방문 x RUNS회를 돈다.
  test.describe.configure({ timeout: 30 * 60 * 1000 });

  test("1차 방문과 2차 방문의 캐시 거동·페인트를 비교한다", async ({ browser }) => {
    const baseUrl = requireBaseUrl();
    const target: SeatMapTarget = requireSeatMapTarget();
    const profile = PROFILES[PROFILE_NAME];

    console.log(`\n[재방문] 조건: ${profile === null ? "none(스로틀 없음 — 진단용)" : profile.label}`);
    console.log(`[재방문] ${RUNS}회 x 경로 3개 x 방문 2회`);

    const routes = [
      { name: "홈", path: "/" },
      { name: "상세", path: `/performances/${target.performanceId}` },
      { name: "좌석맵", path: seatMapPath(target) },
    ];
    const phases = ["cold", "warm"] as const;

    const samples = new Map<string, VisitResult[]>();
    const key = (route: string, phase: (typeof phases)[number]) => `${route}|${phase}`;
    for (const route of routes) {
      for (const phase of phases) samples.set(key(route.name, phase), []);
    }

    for (let run = 1; run <= RUNS; run += 1) {
      for (const route of routes) {
        // ★ 경로마다 context를 새로 만든다 ★
        //   처음에는 회차당 context 하나로 세 경로를 돌았는데, 그러면 홈을 방문한 뒤
        //   상세의 "1차"가 이미 `memory 11`이 된다 — 폰트가 경로 간 공유 자원이라서다.
        //   그 표를 그대로 믿었으면 "상세는 첫 방문도 캐시 히트"라는 말이 안 되는 결론이
        //   나왔을 것이다. 각 경로의 1차 방문은 **빈 캐시**여야 정의가 선다.
        const context = await browser.newContext({ ignoreHTTPSErrors: true });
        const page = await context.newPage();
        const client = await page.context().newCDPSession(page);
        const collector = await createCollector(client);
        await applyProfile(client, PROFILE_NAME);
        try {
          const url = `${baseUrl}${route.path}`;
          samples.get(key(route.name, "cold"))!.push(await visit(page, url, collector));
          samples.get(key(route.name, "warm"))!.push(await visit(page, url, collector));
        } finally {
          await context.close();
        }
      }
      process.stdout.write(`  run ${run}/${RUNS} 완료\n`);
    }

    // --- 표 0: 회차별 FCP ---
    // ★ 첫 6회에서 홈 1차 FCP 산포가 48,979%로 나왔다 ★
    //   중앙값 306ms에 범위가 150초쯤이라는 뜻인데, **회차별 값을 안 찍어서 어느 회차가 왜
    //   그랬는지 알 수 없었다.** 016의 LH 하네스가 회차별 표를 먼저 찍고 그 다음에 중앙값을
    //   내는 이유가 이것이다. 이상치를 자동으로 빼는 장치를 먼저 만들면 원인을 안 보고
    //   버리게 되므로, 일단 보이게만 한다 (009가 "데이터가 빠진 페이지가 정상 회차와
    //   구분되지 않는다"로 남긴 자리의 반대편).
    const runRows = [];
    for (const route of routes) {
      for (const phase of phases) {
        const list = samples.get(key(route.name, phase))!;
        const row: Record<string, string | number> = {
          경로: route.name,
          방문: phase === "cold" ? "1차" : "2차",
        };
        list.forEach((item, index) => {
          row[`r${index + 1}`] = round(item.fcp, 1);
        });
        runRows.push(row);
      }
    }
    printTable("회차별 FCP ms", runRows);

    // --- 표 1: 페인트 지표 ---
    const paintRows = [];
    for (const route of routes) {
      for (const phase of phases) {
        const list = samples.get(key(route.name, phase))!;
        const fcps = list.map((item) => item.fcp);
        const lcps = list.map((item) => item.lcp);
        paintRows.push({
          경로: route.name,
          방문: phase === "cold" ? "1차(빈 캐시)" : "2차(재방문)",
          "FCP ms": round(median(fcps), 1),
          "FCP 산포 %": round(spread(fcps) * 100, 1),
          "LCP ms": round(median(lcps), 1),
          "LCP 산포 %": round(spread(lcps) * 100, 1),
        });
      }
    }
    printTable(`재방문 페인트 지표 — ${PROFILE_NAME} (${RUNS}회 중앙값)`, paintRows);

    // --- 표 2: 폰트 조각의 캐시 거동 ---
    // 이게 이 측정의 과녁이다. 009_1이 걱정한 "조건부 요청 11번"이 실제로 나가는지 본다.
    const fontRows = [];
    for (const route of routes) {
      for (const phase of phases) {
        const first = samples.get(key(route.name, phase))![0];
        const fonts = first.entries.filter((entry) => FONT_PATTERN.test(entry.url));
        fontRows.push({
          경로: route.name,
          방문: phase === "cold" ? "1차" : "2차",
          조각: fonts.length,
          캐시: cacheSummary(fonts),
          "전송B(네트워크)": fonts.reduce((sum, entry) => sum + entry.encodedBytes, 0).toLocaleString(),
          프로토콜: [...new Set(fonts.map((entry) => entry.protocol))].join(","),
          "cache-control": [...new Set(fonts.map((entry) => entry.cacheControl))].join(" | "),
        });
      }
    }
    printTable("폰트 조각 캐시 거동 (1회차 기준)", fontRows);

    // --- 표 3: 전체 요청의 캐시 분포 ---
    const allRows = [];
    for (const route of routes) {
      for (const phase of phases) {
        const first = samples.get(key(route.name, phase))![0];
        allRows.push({
          경로: route.name,
          방문: phase === "cold" ? "1차" : "2차",
          요청: first.entries.length,
          캐시: cacheSummary(first.entries),
          "전송KB(네트워크)": round(
            first.entries.reduce((sum, entry) => sum + entry.encodedBytes, 0) / 1024,
            1
          ),
        });
      }
    }
    printTable("전체 요청 캐시 분포 (1회차 기준)", allRows);

    // --- 표 4: 2차 방문에 남는 네트워크 요청 ---
    // ★ 첫 스모크의 수확이 이 표를 만들게 했다 ★
    //   폰트 조각 11번은 `immutable` 덕에 아예 나가지 않는데(전부 memory 히트, 전송 0B),
    //   2차 방문에도 홈 기준 network 22개 / 27.7KB가 남았다. 즉 **재방문 비용의 실체는
    //   009_1이 걱정한 폰트가 아니다.** 무엇이 남는지 타입으로 갈라야 그 다음 질문이 생긴다
    //   (예: 문서가 `force-dynamic`이라 캐시 대상이 아닌 몫인지, RSC 페이로드인지).
    const warmNetworkRows = [];
    for (const route of routes) {
      const first = samples.get(key(route.name, "warm"))![0];
      const networked = first.entries.filter(
        (entry) => entry.cache === "network" || entry.cache === "304"
      );
      const byType = new Map<string, { count: number; bytes: number; sample: string }>();
      for (const entry of networked) {
        const aggregate = byType.get(entry.resourceType) ?? {
          count: 0,
          bytes: 0,
          // 예시 URL을 하나 남긴다. 타입만 보면 "Fetch 21개"가 무엇인지 알 수 없는데,
          // 그게 Next의 링크 프리페치인지 API 호출인지에 따라 다음 조치가 완전히 달라진다.
          sample: new URL(entry.url).pathname,
        };
        aggregate.count += 1;
        aggregate.bytes += entry.encodedBytes;
        byType.set(entry.resourceType, aggregate);
      }
      const sorted = [...byType.entries()].sort((a, b) => b[1].bytes - a[1].bytes);
      for (const [type, aggregate] of sorted) {
        warmNetworkRows.push({
          경로: route.name,
          타입: type,
          요청: aggregate.count,
          전송B: aggregate.bytes.toLocaleString(),
          "예시 경로": aggregate.sample.length > 46 ? `${aggregate.sample.slice(0, 45)}…` : aggregate.sample,
        });
      }
    }
    printTable("2차 방문에 남는 네트워크 요청 — 캐시로 못 없앤 몫 (1회차 기준)", warmNetworkRows);

    // --- 무결성 ---
    // 1차 방문에 폰트 조각이 하나도 없으면 엉뚱한 것을 재고 있다(글꼴이 안 걸린 페이지).
    // 조각 수를 11로 고정하지 않는 이유: 조각은 실제로 쓰인 글자 범위에 따라 달라지고,
    // 시드 데이터가 바뀌면 그 수도 바뀐다. 하한만 그물로 둔다.
    for (const route of routes) {
      const cold = samples
        .get(key(route.name, "cold"))![0]
        .entries.filter((entry) => FONT_PATTERN.test(entry.url));
      expect(cold.length, `${route.name} 1차 방문에 woff2 요청이 없다`).toBeGreaterThan(0);

      // ★ 각 경로의 1차 방문은 빈 캐시여야 한다 ★
      //   이 그물이 위 "context를 경로마다 새로 만든다"를 집행한다. 경로 간에 캐시가 새면
      //   1차 방문에 memory/disk 히트가 섞이고, 그러면 1차 vs 2차 비교가 무의미해진다.
      const cachedInCold = cold.filter((entry) => entry.cache === "memory" || entry.cache === "disk");
      expect(
        cachedInCold.length,
        `${route.name} 1차 방문인데 폰트가 캐시에서 왔다 (context가 재사용됐다)`
      ).toBe(0);

      // 2차 방문의 조각 수가 1차와 다르면 같은 글자 범위를 보는 비교가 아니다.
      const warm = samples
        .get(key(route.name, "warm"))![0]
        .entries.filter((entry) => FONT_PATTERN.test(entry.url));
      expect(warm.length, `${route.name}의 1차/2차 조각 수가 다르다`).toBe(cold.length);
    }
  });
});
