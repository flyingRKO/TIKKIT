"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { ApiError } from "@/lib/api/client";
import { getScheduleSeats } from "@/lib/api/performances";
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
  SOLD_OUT: "선택한 등급의 잔여 좌석이 부족합니다. 수량을 줄이거나 다른 등급을 선택해주세요.",
  BOOKING_NOT_OPEN: "지금은 예매 가능한 기간이 아닙니다.",
  VALIDATION_ERROR: "선택한 회차·등급·수량을 다시 확인해주세요.",
  NOT_FOUND: "선택한 회차 또는 등급을 찾을 수 없습니다. 페이지를 새로고침해주세요.",
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

// 등급에서 예매 가능한 좌석을 앞자리부터 quantity개 골라준다. 좌석이 모자라면 null.
//
// ⚠️ 한시적 코드다. Task 023에서 좌석 배치도가 생기면 사용자가 고른 seatIds를 폼으로 받게 되고,
//    이 함수는 사라진다. 지금은 선택 UI가 없는데 BE는 seatIds를 필수로 받으므로 서버가 대신 고른다.
//    컴포넌트(ticket-selector.tsx)를 손대지 않으려고 Server Action 안에 둔 것이다.
//
// BE 응답이 이미 배치도 순서(앞열 → 왼쪽)로 정렬돼 있어서 slice만 하면 앞자리가 잡힌다.
// 좌석을 고른 뒤 선점 요청 사이에 다른 사람이 그 자리를 가져갈 수 있는데, 그건 BE가 SOLD_OUT으로
// 끊어준다 — 여기서 막을 수 있는 경쟁이 아니다.
async function pickAvailableSeatIds(
  scheduleId: number,
  ticketGradeId: number,
  quantity: number
): Promise<number[] | null> {
  const seats = await getScheduleSeats(scheduleId);
  const picked = seats
    .filter((seat) => seat.ticketGradeId === ticketGradeId && seat.status === "AVAILABLE")
    .slice(0, quantity)
    .map((seat) => seat.id);

  return picked.length === quantity ? picked : null;
}

export async function createReservationAction(
  _prevState: ReservationActionState,
  formData: FormData
): Promise<ReservationActionState> {
  const performanceId = Number(formData.get("performanceId"));
  const scheduleId = Number(formData.get("scheduleId"));
  const ticketGradeId = Number(formData.get("ticketGradeId"));
  const quantity = Number(formData.get("quantity"));
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

  // redirect()는 내부적으로 예외를 던져서 동작하므로 try/catch 안에서 부르면 안 된다.
  // 그래서 이동할 곳만 변수에 담고 블록 밖에서 redirect 한다.
  let redirectTo: string;

  try {
    const seatIds = await pickAvailableSeatIds(scheduleId, ticketGradeId, quantity);
    if (seatIds === null) {
      // 화면에 남아 있는 잔여석 숫자가 오래된 값이다. 다시 가져오게 한다.
      revalidatePath(`/performances/${performanceId}`);
      return { error: CREATE_ERROR_MESSAGES.SOLD_OUT };
    }

    const reservation = await createReservation({ scheduleId, ticketGradeId, quantity, seatIds });
    redirectTo = `/booking/${reservation.id}`;
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === "UNAUTHORIZED") {
        // 세션이 만료된 채로 예매를 눌렀다. 로그인 후 이 공연 페이지로 돌아오게 한다.
        redirect(`/login?redirect=${encodeURIComponent(`/performances/${performanceId}`)}`);
      }

      if (error.code === "DUPLICATE_PENDING_RESERVATION") {
        const existingId = await findPendingReservationId(performanceTitle, showAt, grade);
        if (existingId !== null) {
          redirect(`/booking/${existingId}`);
        }
        return { error: "이미 선점 중인 예약이 있습니다. 마이페이지에서 확인해주세요." };
      }

      if (error.code === "SOLD_OUT" || error.code === "BOOKING_NOT_OPEN") {
        // 화면에 남아 있는 잔여석 숫자가 오래된 값일 수 있으니 다시 가져오게 한다.
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
