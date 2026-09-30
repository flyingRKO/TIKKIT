import type { PaymentMethod } from "@/types/api";

// "use server" 파일(lib/actions/reservation.ts)은 export가 전부 async 함수여야 해서, 상수/타입은 따로 둔다.
export interface ReservationActionState {
  error: string | null;
}

export const initialReservationActionState: ReservationActionState = { error: null };

// 모의 결제수단. BE는 잘못된 enum 값이 오면 400이 아니라 500을 주기 때문에 FE에서 허용값을 먼저 검증한다.
export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  CARD: "신용·체크카드",
  KAKAO_PAY: "카카오페이",
  BANK_TRANSFER: "무통장 입금",
};

export const PAYMENT_METHODS = Object.keys(PAYMENT_METHOD_LABELS) as PaymentMethod[];
