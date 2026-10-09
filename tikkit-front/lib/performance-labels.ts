import type { Grade, PerformanceCategory, PerformanceStatus } from "@/types/api";

// 백엔드 enum 값 ↔ 화면에 보여줄 한글 라벨 매핑. 여러 컴포넌트(카드, 필터, 뱃지)에서 공유한다.

export const CATEGORY_LABELS: Record<PerformanceCategory, string> = {
  CONCERT: "콘서트",
  MUSICAL: "뮤지컬",
  THEATER: "연극",
  CLASSIC: "클래식",
  SPORTS: "스포츠",
};

export const STATUS_LABELS: Record<PerformanceStatus, string> = {
  UPCOMING: "오픈 예정",
  ON_SALE: "예매중",
  CLOSED: "판매종료",
};

export const GRADE_LABELS: Record<Grade, string> = {
  VIP: "VIP",
  R: "R석",
  S: "S석",
  A: "A석",
};

export const CATEGORY_OPTIONS: PerformanceCategory[] = [
  "CONCERT",
  "MUSICAL",
  "THEATER",
  "CLASSIC",
  "SPORTS",
];

export const STATUS_OPTIONS: PerformanceStatus[] = ["UPCOMING", "ON_SALE", "CLOSED"];

/**
 * "VIP석 중앙 1열 3번". 배치도의 좌석 aria-label, 선택 요약, 주문 요약이 같은 문구를 쓴다.
 *
 * 좌석맵 모듈이 아니라 여기 둔 이유: 배치도를 안 그리는 화면(결제·예매 상세)도 쓰기 때문이다.
 */
export function formatSeatLabel(seat: {
  section: string;
  rowLabel: string;
  seatNumber: number;
}): string {
  return `${seat.section} ${seat.rowLabel}열 ${seat.seatNumber}번`;
}
