/**
 * 성능 측정 대상 지정 (Task 030).
 *
 * 측정 대상은 prod 스택(docker-compose.prod.yml)이다. 브라우저 -> nginx -> frontend ->
 * nginx -> backend 경로를 그대로 타야 실제 사용자와 같은 것을 재는 것이 된다.
 *
 * 좌석맵 URL을 코드에 박지 않는 이유: 99-teardown.sql로 시드를 갈아끼우면 시퀀스가
 * 이어져서 공연·회차 id가 매번 달라진다. 시딩 직후 psql로 조회한 값을 환경변수로 넘긴다.
 */

export interface SeatMapTarget {
  performanceId: number;
  scheduleId: number;
  gradeId: number;
  quantity: number;
}

/**
 * E2E_BASE_URL이 없으면 실패시킨다.
 *
 * playwright.config.ts의 기본값은 http://localhost:3000인데, 그건 `next start`가 띄운
 * 다른 서버이거나 아예 떠 있지 않다. 004 10절 "함정 4"(e2e가 next build 안 한 옛 번들을
 * 보고 있었다)와 같은 종류의 사고라서, 조용히 엉뚱한 것을 재느니 실패하는 쪽을 고른다.
 */
export function requireBaseUrl(): string {
  const baseUrl = process.env.E2E_BASE_URL;
  if (baseUrl === undefined || baseUrl === "") {
    throw new Error(
      "E2E_BASE_URL이 필요하다. 성능 측정은 prod 스택(nginx 경유)을 대상으로만 의미가 있다.\n" +
        "  예: E2E_BASE_URL=http://localhost npm run perf"
    );
  }
  if (baseUrl.includes(":3000")) {
    throw new Error(
      `E2E_BASE_URL이 ${baseUrl}이다. 3000번은 FE 컨테이너 내부 포트라 nginx를 건너뛴다.\n` +
        "  prod 스택의 외부 진입점은 80번 하나뿐이다: E2E_BASE_URL=http://localhost"
    );
  }
  return baseUrl.replace(/\/$/, "");
}

function requireIntEnv(name: string): number {
  const raw = process.env[name];
  const value = Number(raw);
  if (raw === undefined || raw === "" || !Number.isInteger(value)) {
    throw new Error(
      `${name}이 필요하다(정수). 시딩 직후 psql로 뽑아서 넘긴다 — 009 문서의 재현 방법 참조.`
    );
  }
  return value;
}

export function requireSeatMapTarget(): SeatMapTarget {
  // 매수는 기본 4(MAX_QUANTITY). 선택 깊이별 비용을 재려면 여유가 있어야 한다.
  const quantity = process.env.PERF_QUANTITY === undefined ? 4 : Number(process.env.PERF_QUANTITY);
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 4) {
    throw new Error("PERF_QUANTITY는 1~4 사이 정수여야 한다 (BE @Size(max = 4)와 같은 값).");
  }
  return {
    performanceId: requireIntEnv("PERF_PERFORMANCE_ID"),
    scheduleId: requireIntEnv("PERF_SCHEDULE_ID"),
    gradeId: requireIntEnv("PERF_GRADE_ID"),
    quantity,
  };
}

export function seatMapPath(target: SeatMapTarget): string {
  return (
    `/performances/${target.performanceId}/schedules/${target.scheduleId}/seats` +
    `?gradeId=${target.gradeId}&quantity=${target.quantity}`
  );
}

/** 표를 stdout에 찍는다. 측정 결과는 파일로 남기지 않고 사람이 문서로 옮긴다(006/008과 같은 방식). */
export function printTable(title: string, rows: Array<Record<string, string | number>>): void {
  if (rows.length === 0) {
    return;
  }
  const columns = Object.keys(rows[0]);
  const widths = columns.map((column) =>
    Math.max(column.length, ...rows.map((row) => String(row[column]).length))
  );
  const line = (cells: string[]) =>
    cells.map((cell, index) => cell.padEnd(widths[index])).join("  ");

  console.log(`\n=== ${title} ===`);
  console.log(line(columns));
  console.log(line(widths.map((width) => "-".repeat(width))));
  for (const row of rows) {
    console.log(line(columns.map((column) => String(row[column]))));
  }
}

/** 중앙값. 측정 반복의 대표값은 006부터 계속 중앙값을 쓴다. */
export function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

export function round(value: number, digits = 2): number {
  return Number(value.toFixed(digits));
}
