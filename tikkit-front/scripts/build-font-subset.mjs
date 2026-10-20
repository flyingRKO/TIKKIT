/**
 * Pretendard Variable 단일 서브셋 생성 (Task 030_2 항목 5).
 *
 * 무엇을 시험하는가
 * ----------------
 * 030_1이 `@import`를 dynamic subset으로 바꿔 전송량 76.8%를 줄였지만, 그 뒤에도 전송량
 * 1순위는 여전히 폰트다(after 홈 536KB 중 289KB = 53.8%). 그리고 요청이 1개 -> 8~11개로
 * 늘었다. nginx가 HTTP/1.1이라 커넥션 6개 제한에 걸려 2라운드가 된다.
 *
 * ROADMAP 항목 5는 "KS X 1001 단일 파일 + next/font/local로 요청 11개 -> 1개"다.
 * 그래서 단일 파일을 실제로 만들어서 재 본다.
 *
 * 글자 범위를 어떻게 정했는가
 * ------------------------
 * KS X 1001의 한글 2,350자 표를 그대로 재현하지 않았다. 그 표는 특정 목록이라 외워서 쓰면
 * 틀린다. 대신 009_1 3.4절이 밝힌 사실을 쓴다 — Pretendard의 dynamic subset 조각은
 * 코드포인트 순이 아니라 **글자 사용 빈도 순**이고 번호가 클수록 흔한 글자다
 * (91 = 라틴·숫자, 90 = 최빈 한글, 0~2 = 거의 안 쓰이는 글자).
 *
 * 그래서 번호가 큰 조각부터 N개의 unicode-range를 합집합으로 모아 그 글자들만 남긴다.
 * 기본 N=24면 약 2,400 코드포인트로 KS X 1001 규모를 근사한다.
 *
 * > 확인 필요: "빈도 상위 2,400자"와 "KS X 1001 2,350자"는 같은 집합이 아니다. 규모만
 * > 맞춘 근사이고, 어느 쪽이 한국어 웹에서 더 잘 덮는지는 재지 않았다.
 *
 * 실행:
 *   npm run build-font-subset
 *   FS_CHUNKS=32 npm run build-font-subset
 */

import { mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import subsetFont from "subset-font";

const PRETENDARD_DIR = "node_modules/pretendard/dist/web/variable";
const SOURCE_FONT = join(PRETENDARD_DIR, "woff2/PretendardVariable.woff2");
const SUBSET_CSS = join(PRETENDARD_DIR, "pretendardvariable-dynamic-subset.css");
const SUBSET_WOFF2_DIR = join(PRETENDARD_DIR, "woff2-dynamic-subset");
const OUTPUT = "app/fonts/PretendardVariable.subset.woff2";

function intEnv(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === "") {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1 || value > 92) {
    throw new Error(`${name}은 1~92 사이 정수여야 한다 (받은 값: ${raw}).`);
  }
  return value;
}

/** "U+ac00-d7a3, U+f900" 같은 문자열을 [start, end] 쌍으로 펼친다. */
function parseUnicodeRange(raw) {
  const ranges = [];
  for (const part of raw.split(",")) {
    const token = part.trim().replace(/^U\+/i, "");
    if (token === "") {
      continue;
    }
    if (token.includes("-")) {
      const [from, to] = token.split("-");
      ranges.push([parseInt(from, 16), parseInt(to, 16)]);
    } else {
      const single = parseInt(token, 16);
      ranges.push([single, single]);
    }
  }
  return ranges;
}

/** dynamic subset CSS에서 조각별 {index, ranges, bytes}를 뽑는다. */
function readChunks() {
  const css = readFileSync(SUBSET_CSS, "utf8");
  const matches = [
    ...css.matchAll(/subset\.(\d+)\.woff2[\s\S]*?unicode-range:\s*([^;]+);/g),
  ];
  if (matches.length === 0) {
    throw new Error(
      "dynamic subset CSS에서 @font-face를 못 읽었다. pretendard 패키지 구조가 바뀌었는지 본다."
    );
  }
  return matches
    .map(([, index, range]) => {
      const file = join(SUBSET_WOFF2_DIR, `PretendardVariable.subset.${index}.woff2`);
      return {
        index: Number(index),
        ranges: parseUnicodeRange(range),
        bytes: statSync(file).size,
      };
    })
    .sort((a, b) => b.index - a.index);
}

const chunkCount = intEnv("FS_CHUNKS", 24);
const chunks = readChunks();
const picked = chunks.slice(0, chunkCount);

const codepoints = new Set();
for (const chunk of picked) {
  for (const [from, to] of chunk.ranges) {
    for (let cp = from; cp <= to; cp += 1) {
      codepoints.add(cp);
    }
  }
}
const text = [...codepoints].map((cp) => String.fromCodePoint(cp)).join("");
const pickedBytes = picked.reduce((sum, chunk) => sum + chunk.bytes, 0);

console.log(`\n[build-font-subset] 조각 ${picked.length}개 (번호 ${picked.at(-1).index}~${picked[0].index})`);
console.log(`  코드포인트 ${codepoints.size.toLocaleString()}자`);
console.log(`  같은 글자를 조각으로 받으면 ${pickedBytes.toLocaleString()}B (${(pickedBytes / 1024).toFixed(1)}KB)`);

const source = readFileSync(SOURCE_FONT);
console.log(`  원본 통짜 ${source.length.toLocaleString()}B (${(source.length / 1024).toFixed(1)}KB)`);

// variationAxes를 주지 않는다 — wght 가변 축(45~920)을 살려야 globals.css의
// font-weight 선언이 그대로 동작한다. 축을 고정하면 굵기가 하나로 박힌다.
const subset = await subsetFont(source, text, { targetFormat: "woff2" });

mkdirSync(dirname(OUTPUT), { recursive: true });
writeFileSync(OUTPUT, subset);

const perGlyph = subset.length / codepoints.size;
console.log(`\n  => ${OUTPUT}`);
console.log(`     ${subset.length.toLocaleString()}B (${(subset.length / 1024).toFixed(1)}KB) / 글자당 ${perGlyph.toFixed(1)}B`);
console.log(`     조각 방식 대비 ${(((subset.length - pickedBytes) / pickedBytes) * 100).toFixed(1)}%`);
console.log(`     원본 대비 ${(((subset.length - source.length) / source.length) * 100).toFixed(1)}%`);
console.log("\n주의: 이 파일은 방문자가 어느 페이지를 보든 전부 내려간다. 지금의 dynamic subset은");
console.log("      그 페이지에 실제로 쓰인 글자의 조각만 받는다(홈 11개 / 상세 8개). 즉 요청 수와");
console.log("      바이트가 서로 반대 방향으로 움직이는 거래다.");
