import type { PaymentMethod } from "@/types/api";

// "use server" 파일(lib/actions/reservation.ts)은 export가 전부 async 함수여야 해서, 상수/타입은 따로 둔다.
export interface ReservationActionState {
  error: string | null;
  /**
   * 고른 좌석을 놓친 경우에만 올라가는 값. 배치도가 이 값의 변화를 보고 선택을 비운다.
   *
   * boolean을 쓰면 같은 실패가 두 번 연속 났을 때 값이 안 바뀌어서 두 번째를 감지할 수 없다.
   * 메시지 문자열도 같은 이유로 신호가 되지 못한다.
   */
  resetSeatsAt?: number;
}

export const initialReservationActionState: ReservationActionState = { error: null };

// 모의 결제수단. BE는 잘못된 enum 값이 오면 400이 아니라 500을 주기 때문에 FE에서 허용값을 먼저 검증한다.
export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  CARD: "신용·체크카드",
  KAKAO_PAY: "카카오페이",
  BANK_TRANSFER: "무통장 입금",
};

export const PAYMENT_METHODS = Object.keys(PAYMENT_METHOD_LABELS) as PaymentMethod[];
