/**
 * 좌석맵 응답의 JSON.parse 비용 (Task 030_2).
 *
 * 왜 따로 재는가
 * -------------
 * ROADMAP이 Task 030_2의 근거를 "전송량이 아니라 확장성"으로 못박았다. 좌석맵 문서 전송이
 * 32.9KB뿐이라 반으로 줄여도 절감이 약 16KB이고, 그 숫자로는 응답 구조 변경을 정당화할 수
 * 없다. 쓸 수 있는 근거는 "좌석 수가 늘 때 비용이 어떻게 늘어나는가"이고, 그 비용의 한
 * 축이 파싱이다.
 *
 * 왜 앱 안에서 안 재는가
 * --------------------
 * Next 서버가 apiFetch 안에서 돌리는 JSON.parse는 요청 처리 전체(fetch 왕복, RSC 직렬화,
 * 렌더)에 섞여 있어서 분리하려면 계측을 심어야 한다. 그러면 계측 자체가 비용에 들어가고
 * before/after에서 그 몫이 달라진다. 여기서는 응답 본문만 떼어내 격리 측정한다.
 *
 * ⚠ 따라서 이 수치는 "사용자가 기다리는 시간"이 아니다. 좌석 수에 대한 파싱 비용의
 *   기울기를 보기 위한 값이고, 문서에도 격리 측정임을 같이 적는다.
 *
 * 실행:
 *   PB_BASE_URL=http://localhost PB_SCHEDULE_ID=123 npm run parse-bench
 *   PB_BASE_URL=http://localhost PB_SCHEDULE_ID=123 PB_RUNS=40 npm run parse-bench
 */

const WARMUP = 5;

function requireEnv(name) {
  const value = process.env[name];
  if (value === undefined || value === "") {
    throw new Error(
      `${name}이 필요하다. 예: PB_BASE_URL=http://localhost PB_SCHEDULE_ID=123 npm run parse-bench`
    );
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

function round(value, digits = 3) {
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

/**
 * 좌석 총수를 응답에서 센다.
 *
 * ApiResponse 래퍼({ data: [...] })와 항목 3-b의 축약 구조({ selectable, others })를 모두
 * 받아준다. 분모가 구조에 따라 달라지면 before/after를 비교할 수 없다.
 */
function countSeats(parsed) {
  const body = Array.isArray(parsed) ? parsed : parsed?.data;
  if (Array.isArray(body)) {
    return body.length;
  }
  const selectable = Array.isArray(body?.selectable) ? body.selectable.length : 0;
  const others = Array.isArray(body?.others) ? body.others.length : 0;
  if (selectable + others === 0) {
    throw new Error("좌석 총수를 응답에서 못 읽었다 — 구조가 바뀌었으면 countSeats도 고쳐라.");
  }
  return selectable + others;
}

const baseUrl = requireEnv("PB_BASE_URL").replace(/\/$/, "");
const scheduleId = requireEnv("PB_SCHEDULE_ID");
const runs = intEnv("PB_RUNS", 20);
const url = `${baseUrl}/api/v1/schedules/${scheduleId}/seats`;

// 압축을 끈다. 켜 두면 측정 대상이 아닌 해제 시간이 fetch 쪽에 섞이고, 응답 바이트가
// 압축본이라 "좌석당 바이트"가 구조 변경과 압축률 변화를 구분하지 못하게 된다.
const response = await fetch(url, { headers: { "Accept-Encoding": "identity" } });
if (!response.ok) {
  throw new Error(`${url}가 ${response.status}를 줬다. 시드/회차 id를 확인해라.`);
}
const text = await response.text();
const bytes = Buffer.byteLength(text, "utf8");
const seatCount = countSeats(JSON.parse(text));

console.log(`\n[parse-bench] ${url}`);
console.log(`  응답 ${bytes.toLocaleString()}B / 좌석 ${seatCount.toLocaleString()}석 / ${runs}회`);

// JIT가 덜 데워진 첫 몇 회는 버린다. 버리지 않으면 중앙값이 회차 수에 따라 흔들린다.
for (let i = 0; i < WARMUP; i += 1) {
  JSON.parse(text);
}

const timings = [];
for (let i = 0; i < runs; i += 1) {
  const startedAt = performance.now();
  const parsed = JSON.parse(text);
  const elapsed = performance.now() - startedAt;
  // 결과를 안 쓰면 엔진이 parse를 통째로 지울 수 있다. 값 하나를 읽어 살려 둔다.
  if (parsed === null) {
    throw new Error("parse 결과가 null이다");
  }
  timings.push(elapsed);
}

const mid = median(timings);
const min = Math.min(...timings);
const max = Math.max(...timings);

printTable(`JSON.parse (격리 측정, n=${runs})`, [
  {
    지표: "1회 소요 ms",
    중앙값: round(mid),
    최소: round(min),
    최대: round(max),
    산포: `${round(mid === 0 ? 0 : ((max - min) / mid) * 100, 1)}%`,
  },
]);

printTable("좌석당 환산", [
  { 항목: "응답 바이트", 값: bytes.toLocaleString(), "좌석당": round(bytes / seatCount, 2) },
  { 항목: "parse 중앙값 ms", 값: round(mid), "좌석당": round(mid / seatCount, 6) },
  {
    항목: "1,000석당 parse ms",
    값: round((mid / seatCount) * 1000, 4),
    "좌석당": "-",
  },
]);

console.log("\n주의: 앱 요청 처리에서 분리한 격리 측정이다. 사용자가 기다리는 시간이 아니고,");
console.log("      좌석 수에 대한 파싱 비용의 기울기를 보기 위한 값이다.");
