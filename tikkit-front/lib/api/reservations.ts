import { apiFetch } from "@/lib/api/client";
import type {
  PageResponse,
  PaymentRequest,
  ReservationCreateRequest,
  ReservationDetailResponse,
  ReservationResponse,
  ReservationStatus,
  ReservationSummaryResponse,
} from "@/types/api";

// 선점(PENDING) 예약을 만든다. 재고 차감과 10분 만료 시각 세팅은 BE가 처리한다.
export function createReservation(request: ReservationCreateRequest): Promise<ReservationResponse> {
  return apiFetch<ReservationResponse>("/api/v1/reservations", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

// 남의 예약이거나 없는 id면 BE가 둘 다 404(NOT_FOUND)로 응답한다 — 호출부에서 notFound()로 처리한다.
export function getReservation(id: number | string): Promise<ReservationDetailResponse> {
  return apiFetch<ReservationDetailResponse>(`/api/v1/reservations/${id}`);
}

export function getMyReservations(
  status?: ReservationStatus
): Promise<PageResponse<ReservationSummaryResponse>> {
  const query = status ? `?status=${status}` : "";
  return apiFetch<PageResponse<ReservationSummaryResponse>>(`/api/v1/reservations${query}`);
}

export function payReservation(
  id: number | string,
  request: PaymentRequest
): Promise<ReservationResponse> {
  return apiFetch<ReservationResponse>(`/api/v1/reservations/${id}/payments`, {
    method: "POST",
    body: JSON.stringify(request),
  });
}
