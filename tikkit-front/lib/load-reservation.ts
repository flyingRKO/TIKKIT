import { notFound, redirect } from "next/navigation";
import { ApiError } from "@/lib/api/client";
import { getReservation } from "@/lib/api/reservations";
import type { ReservationDetailResponse } from "@/types/api";

// 선점 만료까지 남은 시간(ms). 서버에서 계산해 클라이언트에 넘긴다 — expiresAt을 그대로 넘기면 브라우저 시계에 의존하게 된다.
// 컴포넌트 밖 함수로 둔 이유: 렌더 중 Date.now() 직접 호출은 react-hooks/purity 린트에 걸린다.
export function getRemainingMs(expiresAt: string | null): number {
  return expiresAt ? new Date(expiresAt).getTime() - Date.now() : 0;
}

// 결제 페이지·완료 페이지가 같이 쓰는 예약 조회. Server Component 전용이다.
// - 숫자가 아닌 id는 BE에 보내지 않고 404 처리한다.
// - NOT_FOUND는 없는 예약이거나 남의 예약이다(BE가 둘을 구분하지 않는다).
// - proxy.ts는 쿠키 "존재"만 보기 때문에, 쿠키는 있는데 BE 세션이 만료된 경우는 여기서 UNAUTHORIZED로 걸러 로그인으로 보낸다.
// loginRedirectPath: 세션이 만료됐을 때 로그인 후 돌아올 경로. 기본값은 결제 페이지다.
export async function loadReservation(
  reservationId: string,
  loginRedirectPath: string = `/booking/${reservationId}`
): Promise<ReservationDetailResponse> {
  if (!/^\d+$/.test(reservationId)) {
    notFound();
  }

  try {
    return await getReservation(reservationId);
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === "NOT_FOUND") {
        notFound();
      }
      if (error.code === "UNAUTHORIZED") {
        redirect(`/login?redirect=${encodeURIComponent(loginRedirectPath)}`);
      }
    }
    throw error;
  }
}
