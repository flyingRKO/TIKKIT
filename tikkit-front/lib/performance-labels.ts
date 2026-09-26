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
