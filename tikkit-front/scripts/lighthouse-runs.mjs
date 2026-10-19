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
      "--chrome-flags=--headless=new --no-sandbox --disable-gpu",
      "--quiet",
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
    fonts: requests
      .filter((item) => item.resourceType === "Font")
      .map((item) => ({ url: item.url, transfer: item.transferSize ?? 0 })),
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
  sample.contaminated =
    expectDocBytes !== null &&
    Math.abs(sample.docBytes - expectDocBytes) / expectDocBytes > INTEGRITY_TOLERANCE;
  samples.push(sample);
  console.log(
    `FCP ${Math.round(sample.fcp)}ms / 문서 ${sample.docBytes.toLocaleString()}B` +
      (sample.contaminated ? "   <-- 오염" : "")
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
    무결성: sample.contaminated ? "오염" : "ok",
  }))
);

const clean = samples.filter((sample) => !sample.contaminated);
if (clean.length === 0) {
  console.log("\n모든 회차가 오염으로 판정됐다. LH_EXPECT_DOC_BYTES가 맞는지 먼저 확인한다.");
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

// 폰트는 개별로 나열한다. 서브셋 전환은 요청 1개 -> N개가 되는 변경이라 합계만 보면
// "바이트는 줄었는데 요청이 30개로 늘어 직렬화됐다"를 놓친다 (nginx가 HTTP/1.1이다).
const fontTotal = first.fonts.reduce((sum, font) => sum + font.transfer, 0);
printTable(
  `${label} — 폰트 요청 ${first.fonts.length}개 / 합계 ${fontTotal.toLocaleString()}B`,
  first.fonts.map((font) => ({
    전송: font.transfer.toLocaleString(),
    파일: font.url.replace(/^https?:\/\/[^/]+/, "").replace(/\?.*$/, ""),
  }))
);

console.log(`\n원본 JSON: ${outDir}`);
