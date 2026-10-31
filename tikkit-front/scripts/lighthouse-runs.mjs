/**
 * Lighthouse 회차 수집 + 무결성 검사 (Task 030_1).
 *
 * Task 030은 회차마다 lighthouse CLI를 손으로 돌리고 JSON에서 지표를 눈으로 꺼냈다.
 * 좌석맵만 6회 x 2군이라 그 작업이 측정 시간보다 오래 걸렸고, 더 나쁜 건 오염 회차를
 * 사람이 걸러야 했다는 점이다 — 009 4.9절에서 데이터가 통째로 빠진 홈 회차와 정상 회차의
 * FCP 차이가 1ms, 총 전송 바이트 차이가 0.6%였다. 폰트 2MB가 분모를 지배해서 "공연 카드가
 * 통째로 빠진 페이지"를 총 바이트로는 구분할 수 없다. 걸러낸 건 문서 본문 크기였다.
 *
 * 그래서 이 스크립트가 하는 일은 셋이다.
 *  1) N회 돌리고 지표를 표로 뽑는다 (중앙값 + 산포)
 *  2) 산포 20% 초과 지표를 "판단 불가"로 표시한다 (008/009가 글로 지킨 규율을 코드로 옮긴다)
 *  3) 문서 resourceSize로 회차별 무결성을 검사한다 (009 4.9절이 요구한 검사)
 *
 * 결과는 006/008/009와 같이 stdout 표로만 낸다. 문서로는 사람이 옮긴다.
 * 원본 JSON은 .lighthouse/ 밑에 남기지만 회차당 약 1MB라 커밋하지 않는다.
 *
 * 실행 (환경변수 접두사를 package.json에 쓸 수 없다 — 009 6.6절, Windows npm이 cmd.exe로 돈다):
 *   LH_URL=http://localhost LH_RUNS=4 LH_LABEL=home-before LH_EXPECT_DOC_BYTES=46039 npm run lh
 *
 * https(로컬 self-signed)로 재는 경우 — 인증서 경고를 넘기고 프로토콜을 검사한다:
 *
 *   LH_URL=https://localhost LH_RUNS=4 LH_LABEL=home-h2 LH_EXPECT_PROTOCOL=h2 \
 *     LH_CHROME_EXTRA=--ignore-certificate-errors npm run lh
 *
 * LH_EXTRA_FLAGS로 lighthouse CLI 플래그를 덧붙일 수 있다 (Task 030_2). 쓰는 이유 —
 * 좌석맵 FCP가 009_1에서 산포 25.2%로 "판단 불가"였고, after에서 5회가 491~494ms에 몰리는데
 * 1회만 370ms로 빠졌다. 그 빠른 모드가 Lantern 시뮬레이션 산물인지 보려면 스로틀을 끈 군이
 * 필요하다. 거기서 사라지면 시뮬레이션 산물로 확정되고, "FCP를 보조 지표로 내린다"는 판단에
 * 근거가 붙는다:
 *   LH_URL=... LH_RUNS=6 LH_LABEL=seatmap-nothrottle LH_EXTRA_FLAGS=--throttling-method=provided npm run lh
 */

import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, rmSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";

const nodeRequire = createRequire(import.meta.url);

// npx를 거치지 않고 lighthouse의 CLI 진입점을 현재 node로 직접 돌린다.
// Windows에서 npx는 .cmd라 shell: true가 필요하고, 그러면 URL 따옴표 처리가 cmd.exe에
// 달려서 조용히 어긋날 여지가 생긴다. 경로를 직접 집으면 그 변수가 없어진다.
const LIGHTHOUSE_CLI = nodeRequire.resolve("lighthouse/cli/index.js");

/**
 * LH_EXTRA_FLAGS를 공백으로 쪼갠다.
 *
 * 값 안에 공백이 들어가는 플래그(--chrome-flags="a b")는 이 방식으로 못 넘긴다. 그건 이미
 * 아래 고정 인자에 있고, 바꿔야 하면 코드를 고치는 쪽이 낫다 — 셸 따옴표 해석을 흉내내기
 * 시작하면 009 6.6절(Windows npm이 cmd.exe로 돈다)과 같은 종류의 함정이 생긴다.
 */
const EXTRA_FLAGS = (process.env.LH_EXTRA_FLAGS ?? "")
  .trim()
  .split(/\s+/)
  .filter((flag) => flag !== "");

/**
 * Chrome 플래그. 위 주석이 "바꿔야 하면 코드를 고치는 쪽이 낫다"고 적어 둔 그 수정이다
 * (Task 039_1).
 *
 * 왜 필요했나: 로컬 TLS를 self-signed로 세워서 https로 재려면 Chrome에
 * `--ignore-certificate-errors`가 필요한데, 그 플래그는 `--chrome-flags` **값 안**에
 * 들어가야 한다. LH_EXTRA_FLAGS는 공백으로 쪼개므로 거기로는 못 넘긴다.
 *
 *   LH_CHROME_EXTRA=--ignore-certificate-errors LH_URL=https://localhost npm run lh
 *
 * 공백 split을 쓰지 않고 문자열로 그대로 이어 붙인다. spawnSync가 shell 없이 argv를
 * 넘기므로 공백이 들어간 하나의 인자로 안전하게 전달된다.
 */
const CHROME_FLAGS = ["--headless=new", "--no-sandbox", "--disable-gpu"]
  .concat((process.env.LH_CHROME_EXTRA ?? "").trim() === "" ? [] : [process.env.LH_CHROME_EXTRA.trim()])
  .join(" ");

/**
 * 기대 프로토콜. 없으면 검사를 건너뛴다.
 *
 * **이게 이 Task의 영구 산출물이다.** Lighthouse의 Lantern 시뮬레이터는 HTTP/2 이득을
 * `request.protocol === 'h2'` 한 줄로 판단한다(ConnectionPool.js — h2면 커넥션 1개 무제한,
 * 아니면 오리진당 6개 제한). 즉 **TLS는 섰는데 h2 협상이 안 되면, 경고 하나 없이
 * HTTP/1.1 모델로 계산된 수치가 나온다.** 지연·바이트·점수가 전부 정상으로 보인다.
 *
 * Task 039가 같은 모양의 함정을 두 번 밟았다(`.env`에 남은 전략값으로 `single` 군이라
 * 믿고 `local`을 쟀고, k6가 Accept-Encoding을 안 보내 압축 군이 비압축을 쟀다). 둘 다
 * "요청이 일을 안 한" 게 아니라 **조건이 안 걸린** 쪽이라 지표가 멀쩡했다 (교훈 10).
 * LH_EXPECT_DOC_BYTES가 문서 바이트로 하는 일을 프로토콜로 한다.
 *
 *   LH_EXPECT_PROTOCOL=h2        # C군(HTTP/2)
 *   LH_EXPECT_PROTOCOL=http/1.1  # A군(평문)·B군(TLS만)
 */
const EXPECT_PROTOCOL = (process.env.LH_EXPECT_PROTOCOL ?? "").trim();

/** 산포가 이 값을 넘으면 수치를 쓰되 "판단 불가"로 표시한다 (008의 규율). */
const UNRELIABLE_SPREAD = 0.2;
/** 문서 본문이 기대값에서 이 비율 이상 벗어나면 그 회차를 오염으로 본다. */
const INTEGRITY_TOLERANCE = 0.05;

function requireEnv(name) {
  const value = process.env[name];
  if (value === undefined || value === "") {
    throw new Error(`${name}이 필요하다. 예: LH_URL=http://localhost LH_RUNS=4 npm run lh`);
  }
  return value;
}

function intEnv(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === "") {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(`${name}은 1 이상 정수여야 한다 (받은 값: ${raw}).`);
  }
  return value;
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

/** (max-min)/중앙값. 006부터 쓰는 수렴 판정식이다. */
function spread(values) {
  const mid = median(values);
  if (mid === 0) {
    return 0;
  }
  return (Math.max(...values) - Math.min(...values)) / mid;
}

function round(value, digits = 2) {
  return Number(value.toFixed(digits));
}

function printTable(title, rows) {
  if (rows.length === 0) {
    return;
  }
  const columns = Object.keys(rows[0]);
  const widths = columns.map((column) =>
    Math.max(column.length, ...rows.map((row) => String(row[column] ?? "").length))
  );
  const line = (cells) => cells.map((cell, index) => cell.padEnd(widths[index])).join("  ");

  console.log(`\n=== ${title} ===`);
  console.log(line(columns));
  console.log(line(widths.map((width) => "-".repeat(width))));
  for (const row of rows) {
    console.log(line(columns.map((column) => String(row[column] ?? ""))));
  }
}

function runLighthouse(url, outPath) {
  const result = spawnSync(
    process.execPath,
    [
      LIGHTHOUSE_CLI,
      url,
      "--preset=desktop",
      "--only-categories=performance",
      "--output=json",
      `--output-path=${outPath}`,
      `--chrome-flags=${CHROME_FLAGS}`,
      "--quiet",
      ...EXTRA_FLAGS,
    ],
    { stdio: ["ignore", "ignore", "inherit"] }
  );
  if (result.status !== 0) {
    throw new Error(`lighthouse가 ${result.status}로 끝났다. 위 stderr를 본다.`);
  }
  return JSON.parse(readFileSync(outPath, "utf8"));
}

/**
 * 회차 1개에서 필요한 값만 꺼낸다.
 *
 * 문서 resourceSize를 따로 꺼내는 게 핵심이다. 총 전송 바이트는 무결성 검사에 쓸 수 없다
 * (009 4.9절). 문서는 network-requests의 첫 Document 항목으로 집는다 — 리다이렉트가 없는
 * 경로만 재므로 Document는 하나다.
 */
function extract(report) {
  const audits = report.audits;
  const numeric = (id) => audits[id]?.numericValue ?? Number.NaN;
  const requests = audits["network-requests"]?.details?.items ?? [];

  const document = requests.find((item) => item.resourceType === "Document");
  const byType = new Map();
  let transferTotal = 0;
  for (const item of requests) {
    // resourceType이 없는 항목(리다이렉트 등)을 Other에 합치면 안 된다. Lighthouse의
    // Other에는 favicon이 들어가는데, 거기에 본문 0바이트 리다이렉트가 섞이면 "favicon이
    // 몇 바이트인가"를 표에서 읽을 수 없게 된다.
    const type = item.resourceType ?? "(타입 없음)";
    const entry = byType.get(type) ?? { type, count: 0, transfer: 0, decoded: 0 };
    entry.count += 1;
    entry.transfer += item.transferSize ?? 0;
    entry.decoded += item.resourceSize ?? 0;
    byType.set(type, entry);
    transferTotal += item.transferSize ?? 0;
  }

  // 프로토콜을 요청 단위로 센다. 합계만 보면 "문서만 h2이고 정적 자원은 1.1"처럼
  // 반쯤 걸린 상태를 놓친다 — 폰트 조각 11개의 줄서기가 이 Task의 과녁이라 특히 그렇다.
  //
  // data:/blob: 요청은 프로토콜이 없거나 스킴 이름이 들어오므로 센지 않는다.
  const protocols = new Map();
  for (const item of requests) {
    if (!/^https?:/.test(item.url ?? "")) continue;
    const protocol = item.protocol ?? "(없음)";
    protocols.set(protocol, (protocols.get(protocol) ?? 0) + 1);
  }

  return {
    score: Math.round((report.categories.performance.score ?? 0) * 100),
    fcp: numeric("first-contentful-paint"),
    lcp: numeric("largest-contentful-paint"),
    si: numeric("speed-index"),
    tbt: numeric("total-blocking-time"),
    cls: numeric("cumulative-layout-shift"),
    ttfb: numeric("server-response-time"),
    transferTotal,
    docBytes: document?.resourceSize ?? 0,
    byType: [...byType.values()],
    protocols: [...protocols.entries()].map(([protocol, count]) => ({ protocol, count })),
    docProtocol: document?.protocol ?? "(없음)",
    fonts: requests
      .filter((item) => item.resourceType === "Font")
      .map((item) => ({
        url: item.url,
        transfer: item.transferSize ?? 0,
        protocol: item.protocol ?? "(없음)",
      })),
  };
}

const url = requireEnv("LH_URL");
const runs = intEnv("LH_RUNS", 3);
const label = process.env.LH_LABEL ?? "run";
const expectDocBytes =
  process.env.LH_EXPECT_DOC_BYTES === undefined || process.env.LH_EXPECT_DOC_BYTES === ""
    ? null
    : Number(process.env.LH_EXPECT_DOC_BYTES);

const outDir = join(process.cwd(), ".lighthouse", label);
rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

console.log(`\n[${label}] ${url} — ${runs}회`);
if (EXTRA_FLAGS.length > 0) {
  // 추가 플래그는 측정 조건을 바꾼다. 어느 회차를 어떤 자로 쟀는지 로그에 남아야
  // 다른 조건의 before/after를 섞는 사고를 막을 수 있다 (007·008이 계속 부딪힌 것).
  console.log(`  추가 플래그: ${EXTRA_FLAGS.join(" ")}`);
}
// chrome 플래그도 같은 이유로 찍는다. 전에는 고정 문자열이라 찍을 이유가 없었는데
// LH_CHROME_EXTRA로 바뀔 수 있게 된 뒤로는 측정 조건의 일부다 (Task 039_1).
console.log(`  chrome 플래그: ${CHROME_FLAGS}`);
if (EXPECT_PROTOCOL === "") {
  console.log("  LH_EXPECT_PROTOCOL이 없다. 프로토콜 검사를 건너뛴다 (첫 측정이면 정상).");
} else {
  console.log(`  기대 프로토콜 ${EXPECT_PROTOCOL} (http/https 요청 전부)`);
}
if (expectDocBytes === null) {
  console.log("  LH_EXPECT_DOC_BYTES가 없다. 무결성 검사를 건너뛴다 (첫 측정이면 정상).");
} else {
  console.log(
    `  무결성 기대 문서 크기 ${expectDocBytes.toLocaleString()}B (오차 ${INTEGRITY_TOLERANCE * 100}%)`
  );
}

const samples = [];
for (let i = 1; i <= runs; i += 1) {
  process.stdout.write(`  run ${i}/${runs} ... `);
  const report = runLighthouse(url, join(outDir, `run-${i}.json`));
  const sample = extract(report);
  // 기대값이 있으면 벗어난 회차를 집계에서 뺀다. 포함한 채 중앙값을 내면 009가 겪은
  // "데이터가 빠진 페이지가 정상 회차와 구분되지 않는다"가 그대로 재발한다.
  const bytesOff =
    expectDocBytes !== null &&
    Math.abs(sample.docBytes - expectDocBytes) / expectDocBytes > INTEGRITY_TOLERANCE;

  // 프로토콜이 하나라도 기대와 다르면 그 회차는 **다른 조건을 잰 것**이다. 전부를 보는
  // 이유: 문서만 h2이고 정적 자원이 1.1이면 Lantern의 커넥션 모델이 섞여서 "절반쯤
  // 걸린" 수치가 나오고, 그건 어느 조건의 수치도 아니다.
  const offProtocol =
    EXPECT_PROTOCOL === ""
      ? []
      : sample.protocols.filter((entry) => entry.protocol !== EXPECT_PROTOCOL);
  sample.protocolOff = offProtocol;
  sample.contaminated = bytesOff || offProtocol.length > 0;
  sample.reason = bytesOff
    ? "문서 바이트"
    : offProtocol.length > 0
      ? `프로토콜(${offProtocol.map((e) => `${e.protocol}×${e.count}`).join(", ")})`
      : "";

  samples.push(sample);
  console.log(
    `FCP ${Math.round(sample.fcp)}ms / 문서 ${sample.docBytes.toLocaleString()}B` +
      ` / ${sample.docProtocol}` +
      (sample.contaminated ? `   <-- 오염: ${sample.reason}` : "")
  );
}

printTable(
  `${label} — 회차별`,
  samples.map((sample, index) => ({
    run: index + 1,
    score: sample.score,
    FCP: Math.round(sample.fcp),
    LCP: Math.round(sample.lcp),
    SI: Math.round(sample.si),
    TBT: Math.round(sample.tbt),
    CLS: round(sample.cls, 4),
    TTFB: Math.round(sample.ttfb),
    "전송 합계": sample.transferTotal.toLocaleString(),
    "문서 B": sample.docBytes.toLocaleString(),
    프로토콜: sample.docProtocol,
    무결성: sample.contaminated ? `오염(${sample.reason})` : "ok",
  }))
);

const clean = samples.filter((sample) => !sample.contaminated);
if (clean.length === 0) {
  console.log(
    "\n모든 회차가 오염으로 판정됐다. LH_EXPECT_DOC_BYTES / LH_EXPECT_PROTOCOL이 맞는지 먼저 확인한다."
  );
  process.exit(1);
}
if (clean.length < samples.length) {
  console.log(`\n오염 ${samples.length - clean.length}회를 집계에서 제외했다.`);
}

const metrics = [
  ["FCP", (sample) => sample.fcp],
  ["LCP", (sample) => sample.lcp],
  ["SI", (sample) => sample.si],
  ["TBT", (sample) => sample.tbt],
  ["CLS", (sample) => sample.cls],
  ["TTFB", (sample) => sample.ttfb],
  ["전송 합계", (sample) => sample.transferTotal],
];
printTable(
  `${label} — 중앙값 / 산포 (n=${clean.length})`,
  metrics.map(([name, pick]) => {
    const values = clean.map(pick);
    const ratio = spread(values);
    const fmt = (value) => (name === "CLS" ? round(value, 4) : Math.round(value).toLocaleString());
    return {
      지표: name,
      중앙값: fmt(median(values)),
      최소: fmt(Math.min(...values)),
      최대: fmt(Math.max(...values)),
      산포: `${round(ratio * 100, 1)}%`,
      판정: ratio > UNRELIABLE_SPREAD ? "판단 불가" : "수렴",
    };
  })
);

// 리소스 타입 분해는 첫 정상 회차 기준이다. 타입별 바이트는 회차 간 산포가 0.1% 수준이라
// (009 7.2) 중앙값을 낼 이유가 없고, 어느 회차를 봤는지 분명한 쪽이 낫다.
const first = clean[0];
printTable(
  `${label} — 리소스 타입 (run ${samples.indexOf(first) + 1})`,
  [...first.byType]
    .sort((a, b) => b.transfer - a.transfer)
    .map((entry) => ({
      타입: entry.type,
      개수: entry.count,
      전송: entry.transfer.toLocaleString(),
      디코드: entry.decoded.toLocaleString(),
      압축: entry.transfer > 0 && entry.transfer < entry.decoded ? "O" : "X",
      "전송 비중": `${round((entry.transfer / first.transferTotal) * 100, 1)}%`,
    }))
);

// 프로토콜 집계 (Task 039_1). 켰다고 믿지 말고 읽는다 — nginx의 `http2 on;` 한 줄이
// 실제로 걸렸는지는 여기서만 보인다. Lantern이 h2를 커넥션 1개 무제한으로 모델링하므로
// 이 표가 "어느 커넥션 모델로 계산된 수치인가"를 결정한다.
printTable(
  `${label} — 프로토콜 (run ${samples.indexOf(first) + 1})`,
  [...first.protocols]
    .sort((a, b) => b.count - a.count)
    .map((entry) => ({ 프로토콜: entry.protocol, 요청: entry.count }))
);

// 폰트는 개별로 나열한다. 서브셋 전환은 요청 1개 -> N개가 되는 변경이라 합계만 보면
// "바이트는 줄었는데 요청이 30개로 늘어 직렬화됐다"를 놓친다.
//
// 그 줄서기가 생기는지는 프로토콜에 달려 있다 — HTTP/1.1이면 오리진당 커넥션 6개 제한에
// 조각 11개가 2라운드로 밀리고, h2면 한 커넥션에 다중화된다. 그래서 조각마다 프로토콜을
// 같이 찍는다 (Task 039_1).
const fontTotal = first.fonts.reduce((sum, font) => sum + font.transfer, 0);
printTable(
  `${label} — 폰트 요청 ${first.fonts.length}개 / 합계 ${fontTotal.toLocaleString()}B`,
  first.fonts.map((font) => ({
    전송: font.transfer.toLocaleString(),
    프로토콜: font.protocol,
    파일: font.url.replace(/^https?:\/\/[^/]+/, "").replace(/\?.*$/, ""),
  }))
);

console.log(`\n원본 JSON: ${outDir}`);
