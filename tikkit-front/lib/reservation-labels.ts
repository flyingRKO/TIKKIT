import type { PaymentStatus, ReservationStatus } from "@/types/api";

// 예약 상태 ↔ 화면 라벨·배지 색 매핑. 목록·상세·필터가 같이 쓴다.

export const RESERVATION_STATUS_LABELS: Record<ReservationStatus, string> = {
  PENDING: "결제 대기",
  CONFIRMED: "예매 완료",
  CANCELLED: "취소",
  EXPIRED: "만료",
};

// components/ui/badge.tsx에 있는 variant만 쓴다.
export const RESERVATION_STATUS_BADGE_VARIANTS: Record<
  ReservationStatus,
  "default" | "secondary" | "outline" | "highlight"
> = {
  PENDING: "highlight",
  CONFIRMED: "default",
  CANCELLED: "secondary",
  EXPIRED: "outline",
};

export const PAYMENT_STATUS_LABELS: Record<PaymentStatus, string> = {
  PAID: "결제 완료",
  REFUNDED: "환불 완료",
};

export const RESERVATION_STATUS_OPTIONS: ReservationStatus[] = [
  "PENDING",
  "CONFIRMED",
  "CANCELLED",
  "EXPIRED",
];
