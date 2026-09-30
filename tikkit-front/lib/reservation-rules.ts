import type { ReservationDetailResponse, ReservationStatus } from "@/types/api";

// 공연 시작 24시간 전까지만 CONFIRMED 예매를 취소할 수 있다. BE(ReservationService)의 규칙과 같은 값이다.
// ⚠️ 이 값은 버튼을 미리 숨기는 용도의 복사본이다. 최종 판단은 BE(CANCEL_DEADLINE_PASSED)가 하므로,
//    BE 규칙이 바뀌면 여기도 같이 고쳐야 한다.
const CANCEL_DEADLINE_MS = 24 * 60 * 60 * 1000;

export interface ReservationView {
  // 화면에 보여줄 상태. BE 만료 배치가 최대 60초 늦게 돌기 때문에, PENDING이어도 expiresAt이 지났으면 EXPIRED로 본다.
  status: ReservationStatus;
  canPay: boolean;
  canCancel: boolean;
  notice: string | null;
}

// 상세 페이지에서 "무슨 상태로 보이고 어떤 버튼을 보여줄지"를 정한다.
// 컴포넌트 밖 함수로 둔 이유: 렌더 중 Date.now() 직접 호출은 react-hooks/purity 린트에 걸린다.
export function resolveReservationView(reservation: ReservationDetailResponse): ReservationView {
  const now = Date.now();

  if (reservation.status === "PENDING") {
    const expired = !reservation.expiresAt || new Date(reservation.expiresAt).getTime() <= now;
    if (expired) {
      return {
        status: "EXPIRED",
        canPay: false,
        canCancel: false,
        notice: "선점 시간이 만료되었습니다. 공연 페이지에서 다시 예매해주세요.",
      };
    }
    return { status: "PENDING", canPay: true, canCancel: true, notice: null };
  }

  if (reservation.status === "CONFIRMED") {
    const deadline = new Date(reservation.scheduleShowAt).getTime() - CANCEL_DEADLINE_MS;
    if (now >= deadline) {
      return {
        status: "CONFIRMED",
        canPay: false,
        canCancel: false,
        notice: "공연 24시간 전부터는 예매를 취소할 수 없습니다.",
      };
    }
    return { status: "CONFIRMED", canPay: false, canCancel: true, notice: null };
  }

  return { status: reservation.status, canPay: false, canCancel: false, notice: null };
}
