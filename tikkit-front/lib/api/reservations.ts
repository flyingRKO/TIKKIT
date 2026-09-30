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

export interface GetMyReservationsParams {
  status?: ReservationStatus;
  page?: number;
  size?: number;
}

// 값이 없는 파라미터는 쿼리스트링에서 뺀다. BE는 잘못된 status를 400(VALIDATION_ERROR)으로 거절하므로
// 호출부에서 유효한 값만 넘겨야 한다.
export function getMyReservations(
  params: GetMyReservationsParams = {}
): Promise<PageResponse<ReservationSummaryResponse>> {
  const query = new URLSearchParams();
  if (params.status) query.set("status", params.status);
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.size !== undefined) query.set("size", String(params.size));
  const search = query.toString();
  return apiFetch<PageResponse<ReservationSummaryResponse>>(
    `/api/v1/reservations${search ? `?${search}` : ""}`
  );
}

// PENDING은 시점 제한 없이, CONFIRMED는 공연 24시간 전까지만 취소된다(그 뒤엔 CANCEL_DEADLINE_PASSED).
// 요청 body가 없어서 JSON.stringify를 하지 않는다.
export function cancelReservation(id: number | string): Promise<ReservationResponse> {
  return apiFetch<ReservationResponse>(`/api/v1/reservations/${id}/cancel`, {
    method: "POST",
  });
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
