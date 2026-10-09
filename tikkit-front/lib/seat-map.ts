import type { ScheduleSeatResponse, TicketGradeResponse } from "@/types/api";

/**
 * 좌석 한 칸이 차지하는 SVG 좌표 단위. posX/posY가 1씩 늘어날 때 이 값만큼 이동한다.
 * posX에는 구역 사이 통로만큼 값이 비어 있어서, 빈 칸이 그대로 통로로 그려진다.
 */
export const SEAT_CELL = 10;

/** 칸 안에서 실제로 칠하는 사각형 크기. 남는 2는 좌석 사이 간격이 된다. */
export const SEAT_SIZE = 8;

/** 무대 표시를 넣을 위쪽 여백 (SVG 좌표 단위). */
const TOP_PADDING = 24;

/**
 * 등급이 바뀌는 줄 앞에 넣는 가로 통로. 구역 이름을 이 틈에 적는다.
 *
 * posY는 등급 구분 없이 1씩 이어지기 때문에, 간격을 넣지 않으면 구역 이름이 윗 등급의
 * 마지막 줄 좌석과 겹친다. 실제 공연장도 등급 사이에 통로가 있어서 모양도 자연스러워진다.
 */
const GRADE_GAP = 11;

export interface SectionLabel {
  section: string;
  /** 구역 가로 중앙 */
  x: number;
  /** 구역에서 무대에 가장 가까운 줄의 위쪽 (통로 안) */
  y: number;
}

export interface SeatMapLayout {
  /** SVG viewBox 크기 */
  width: number;
  height: number;
  /** 좌석 수 (빈 배열 판정에 쓴다) */
  seatCount: number;
  sections: SectionLabel[];
  /** 무대 사각형을 그릴 영역 */
  stage: { x: number; y: number; width: number; height: number };
  /** 가로 좌표를 0부터 시작하게 당기는 보정값 */
  minPosX: number;
  /**
   * posY → 그 줄의 y 좌표. 등급 사이 통로가 반영돼 있어서 단순 곱셈으로 구할 수 없다.
   * 배열이 아니라 객체인 이유는 Server Component가 그대로 직렬화해 넘기기 때문이다.
   */
  rowY: Record<number, number>;
}

/**
 * 좌석 좌표에서 배치도 크기와 구역 라벨 위치를 계산한다.
 *
 * 공연장 규모가 제각각이라(고척 80열 / 블루스퀘어 19열) 크기를 상수로 둘 수 없고,
 * 좌석 좌표에서 매번 역산한다.
 */
export function buildSeatMapLayout(seats: ScheduleSeatResponse[]): SeatMapLayout {
  if (seats.length === 0) {
    return {
      width: 0,
      height: 0,
      seatCount: 0,
      sections: [],
      stage: { x: 0, y: 0, width: 0, height: 0 },
      minPosX: 0,
      rowY: {},
    };
  }

  let minPosX = Infinity;
  let maxPosX = -Infinity;
  // 줄마다 어느 등급인지. 좌·중·우 블록이 같은 등급이라 줄당 하나로 본다.
  const gradeByRow = new Map<number, number>();
  // 구역별 가로 범위와 맨 앞줄. 한 번의 순회로 모아서 좌석 수천 개를 여러 번 훑지 않는다.
  const bounds = new Map<string, { minX: number; maxX: number; minY: number }>();

  for (const seat of seats) {
    if (seat.posX < minPosX) minPosX = seat.posX;
    if (seat.posX > maxPosX) maxPosX = seat.posX;
    if (!gradeByRow.has(seat.posY)) gradeByRow.set(seat.posY, seat.ticketGradeId);

    const found = bounds.get(seat.section);
    if (found === undefined) {
      bounds.set(seat.section, { minX: seat.posX, maxX: seat.posX, minY: seat.posY });
    } else {
      if (seat.posX < found.minX) found.minX = seat.posX;
      if (seat.posX > found.maxX) found.maxX = seat.posX;
      if (seat.posY < found.minY) found.minY = seat.posY;
    }
  }

  // 줄 번호 순서대로 y를 쌓는다. 등급이 바뀌는 지점에서만 통로를 넣는다.
  const rows = [...gradeByRow.keys()].sort((a, b) => a - b);
  const rowY: Record<number, number> = {};
  let cursorY = TOP_PADDING;
  let previousGradeId: number | null = null;
  for (const row of rows) {
    const gradeId = gradeByRow.get(row)!;
    if (previousGradeId !== null && gradeId !== previousGradeId) {
      cursorY += GRADE_GAP;
    }
    rowY[row] = cursorY;
    cursorY += SEAT_CELL;
    previousGradeId = gradeId;
  }

  const width = (maxPosX - minPosX + 1) * SEAT_CELL;

  const sections: SectionLabel[] = [];
  for (const [section, bound] of bounds) {
    sections.push({
      section,
      x: ((bound.minX + bound.maxX) / 2 - minPosX + 0.5) * SEAT_CELL,
      // 통로 안쪽, 첫 줄 바로 위에 적는다
      y: rowY[bound.minY] - 3,
    });
  }

  return {
    width,
    height: cursorY,
    seatCount: seats.length,
    sections,
    // 무대는 가로 60%를 차지하게 가운데 둔다. 실제 무대 폭 정보가 없어서 보기 좋은 비율로 잡은 값이다.
    stage: { x: width * 0.2, y: 0, width: width * 0.6, height: 14 },
    minPosX,
    rowY,
  };
}

export function seatX(seat: ScheduleSeatResponse, layout: SeatMapLayout): number {
  return (seat.posX - layout.minPosX) * SEAT_CELL + (SEAT_CELL - SEAT_SIZE) / 2;
}

export function seatY(seat: ScheduleSeatResponse, layout: SeatMapLayout): number {
  return layout.rowY[seat.posY] + (SEAT_CELL - SEAT_SIZE) / 2;
}

/**
 * 좌석 응답에는 grade/price가 없고 ticketGradeId만 있다. 색과 가격을 붙이려면 등급 목록과 맞춰야 한다.
 */
export function toGradeMap(grades: TicketGradeResponse[]): Map<number, TicketGradeResponse> {
  return new Map(grades.map((grade) => [grade.id, grade]));
}

