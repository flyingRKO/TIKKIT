"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { ApiError } from "@/lib/api/client";
import {
  cancelReservation,
  createReservation,
  getMyReservations,
  payReservation,
} from "@/lib/api/reservations";
import {
  PAYMENT_METHODS,
  type ReservationActionState,
} from "@/lib/actions/reservation-state";
import type { PaymentMethod } from "@/types/api";

// BE 기본 메시지("잔여 좌석이 없습니다." 등)보다 사용자가 다음에 뭘 해야 하는지 알려주는 문구로 바꿔서 보여준다.
// 여기 없는 코드는 BE 메시지를 그대로 쓴다.
const CREATE_ERROR_MESSAGES: Record<string, string> = {
  SOLD_OUT: "방금 다른 분이 그 좌석을 선점했습니다. 배치도를 새로 불러왔으니 다시 골라주세요.",
  BOOKING_NOT_OPEN: "지금은 예매 가능한 기간이 아닙니다.",
  VALIDATION_ERROR: "선택한 회차·등급·수량을 다시 확인해주세요.",
  NOT_FOUND: "선택한 좌석을 찾을 수 없습니다. 배치도를 새로 불러왔으니 다시 골라주세요.",
  SEAT_MISMATCH: "고른 좌석과 매수가 맞지 않습니다. 좌석을 다시 골라주세요.",
};

const PAY_ERROR_MESSAGES: Record<string, string> = {
  RESERVATION_EXPIRED: "선점 시간이 만료되었습니다. 공연 페이지에서 다시 예매해주세요.",
  INVALID_STATUS_TRANSITION: "이미 처리된 예약입니다. 페이지를 새로고침해주세요.",
};

const CANCEL_ERROR_MESSAGES: Record<string, string> = {
  CANCEL_DEADLINE_PASSED: "공연 24시간 전까지만 취소할 수 있습니다.",
  INVALID_STATUS_TRANSITION: "이미 처리된 예약입니다. 페이지를 새로고침해주세요.",
  NOT_FOUND: "예약을 찾을 수 없습니다.",
};

function toErrorMessage(error: unknown, messages: Record<string, string>): string {
  if (error instanceof ApiError) {
    return messages[error.code] ?? (error.errors.length > 0 ? error.errors.join(" ") : error.message);
  }
  throw error;
}

function isPositiveInteger(value: number): boolean {
  return Number.isInteger(value) && value > 0;
}

/** 좌석 선택 화면 경로. 등급·매수가 쿼리에 있어야 그 화면이 성립한다. */
function toSeatsPath(
  performanceId: number,
  scheduleId: number,
  ticketGradeId: number,
  quantity: number
): string {
  return `/performances/${performanceId}/schedules/${scheduleId}/seats?gradeId=${ticketGradeId}&quantity=${quantity}`;
}

// 기존 선점 예약을 찾는다. BE 목록 응답(ReservationSummaryResponse)에 scheduleId/ticketGradeId가 없어서
// 공연명 + 회차 시각 + 등급으로 맞춰본다.
// ⚠️ 확신 낮음: 같은 이름의 공연이 여러 개이고 회차 시각·등급까지 같으면 다른 예약이 잡힐 수 있다.
//    정확히 하려면 BE 응답에 scheduleId/ticketGradeId를 추가해야 하는데, FE/BE 계약 변경이라 이번 Task에서는 하지 않았다.
async function findPendingReservationId(
  performanceTitle: string,
  showAt: string,
  grade: string
): Promise<number | null> {
  const pending = await getMyReservations({ status: "PENDING" });
  const showAtTime = new Date(showAt).getTime();

  const matched = pending.content.find(
    (reservation) =>
      reservation.performanceTitle === performanceTitle &&
      reservation.grade === grade &&
      new Date(reservation.scheduleShowAt).getTime() === showAtTime
  );
  return matched?.id ?? null;
}

export async function createReservationAction(
  _prevState: ReservationActionState,
  formData: FormData
): Promise<ReservationActionState> {
  const performanceId = Number(formData.get("performanceId"));
  const scheduleId = Number(formData.get("scheduleId"));
  const ticketGradeId = Number(formData.get("ticketGradeId"));
  const quantity = Number(formData.get("quantity"));
  // 좌석은 hidden input 여러 개로 들어온다. 쉼표로 이어붙이면 파싱이 한 단계 더 생긴다.
  const seatIds = formData.getAll("seatIds").map(Number);
  // 중복 선점 시 기존 예약을 찾는 데만 쓰는 값이다. 조작돼도 본인 예약 목록 안에서 매칭될 뿐이라 권한 문제는 없다.
  const performanceTitle = String(formData.get("performanceTitle") ?? "");
  const showAt = String(formData.get("showAt") ?? "");
  const grade = String(formData.get("grade") ?? "");

  if (
    !isPositiveInteger(performanceId) ||
    !isPositiveInteger(scheduleId) ||
    !isPositiveInteger(ticketGradeId) ||
    !isPositiveInteger(quantity)
  ) {
    return { error: CREATE_ERROR_MESSAGES.VALIDATION_ERROR };
  }

  // 좌석 수가 매수와 다르거나 중복이면 BE도 400으로 끊지만, 먼저 거르면 왕복 한 번을 아끼고
  // "좌석을 다시 고르세요"라는 더 정확한 문구를 줄 수 있다.
  if (
    seatIds.length !== quantity ||
    !seatIds.every(isPositiveInteger) ||
    new Set(seatIds).size !== seatIds.length
  ) {
    return { error: CREATE_ERROR_MESSAGES.SEAT_MISMATCH, resetSeatsAt: Date.now() };
  }

  const seatsPath = toSeatsPath(performanceId, scheduleId, ticketGradeId, quantity);

  // redirect()는 내부적으로 예외를 던져서 동작하므로 try/catch 안에서 부르면 안 된다.
  // 그래서 이동할 곳만 변수에 담고 블록 밖에서 redirect 한다.
  let redirectTo: string;

  try {
    const reservation = await createReservation({ scheduleId, ticketGradeId, quantity, seatIds });
    redirectTo = `/booking/${reservation.id}`;
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === "UNAUTHORIZED") {
        // 세션이 만료된 채로 예매를 눌렀다. 로그인 후 같은 등급·매수로 좌석 선택 화면에 돌아오게 한다.
        // 고른 좌석까지 되살리지는 않는다 — 돌아오는 사이에 남이 가져갔을 수 있다.
        redirect(`/login?redirect=${encodeURIComponent(seatsPath)}`);
      }

      if (error.code === "DUPLICATE_PENDING_RESERVATION") {
        const existingId = await findPendingReservationId(performanceTitle, showAt, grade);
        if (existingId !== null) {
          redirect(`/booking/${existingId}`);
        }
        return { error: "이미 선점 중인 예약이 있습니다. 마이페이지에서 확인해주세요." };
      }

      // 누가 먼저 좌석을 잡았거나(SOLD_OUT), 좌석이 이 회차·등급에 없다(NOT_FOUND).
      // 배치도를 다시 받아오고 선택을 비운다 — 화면에 남은 좌석 상태가 이미 낡았다.
      if (error.code === "SOLD_OUT" || error.code === "NOT_FOUND") {
        revalidatePath(seatsPath);
        return { error: toErrorMessage(error, CREATE_ERROR_MESSAGES), resetSeatsAt: Date.now() };
      }

      if (error.code === "BOOKING_NOT_OPEN") {
        revalidatePath(`/performances/${performanceId}`);
      }
    }
    return { error: toErrorMessage(error, CREATE_ERROR_MESSAGES) };
  }

  redirect(redirectTo);
}

export async function payReservationAction(
  _prevState: ReservationActionState,
  formData: FormData
): Promise<ReservationActionState> {
  const reservationId = Number(formData.get("reservationId"));
  const method = String(formData.get("method") ?? "");

  if (!isPositiveInteger(reservationId)) {
    return { error: "잘못된 예약입니다." };
  }
  if (!PAYMENT_METHODS.includes(method as PaymentMethod)) {
    return { error: "결제수단을 선택해주세요." };
  }

  try {
    await payReservation(reservationId, { method: method as PaymentMethod });
  } catch (error) {
    if (error instanceof ApiError && error.code === "UNAUTHORIZED") {
      redirect(`/login?redirect=${encodeURIComponent(`/booking/${reservationId}`)}`);
    }
    return { error: toErrorMessage(error, PAY_ERROR_MESSAGES) };
  }

  redirect(`/booking/${reservationId}/complete`);
}

export async function cancelReservationAction(
  _prevState: ReservationActionState,
  formData: FormData
): Promise<ReservationActionState> {
  const reservationId = Number(formData.get("reservationId"));

  if (!isPositiveInteger(reservationId)) {
    return { error: "잘못된 예약입니다." };
  }

  try {
    await cancelReservation(reservationId);
  } catch (error) {
    if (error instanceof ApiError && error.code === "UNAUTHORIZED") {
      redirect(`/login?redirect=${encodeURIComponent(`/my/reservations/${reservationId}`)}`);
    }
    return { error: toErrorMessage(error, CANCEL_ERROR_MESSAGES) };
  }

  // 취소하면 상태(취소/환불)와 재고가 바뀌므로, 캐시된 목록을 버리고 목록으로 보낸다.
  revalidatePath("/my/reservations");
  redirect("/my/reservations");
}
