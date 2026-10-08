package com.tikkit.api.domain.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 예매 선점 요청.
 * <p>
 * {@code seatIds}는 {@code schedule_seats.id}다 — 물리 좌석({@code seats.id})이 아니다.
 * 좌석은 공연장 단위로 공유되지만 선점은 회차 단위라서, 물리 좌석 ID만으로는 어느 회차의 그 좌석인지
 * 알 수 없다.
 * <p>
 * {@code ticketGradeId}와 {@code quantity}를 지정석 전환 후에도 남겨둔다 — "한 예약 = 한 등급" 정책이
 * 유지되고, {@code reservations}의 단가·금액 스냅샷이 그 등급에서 나온다 (docs/ERD.md 2절).
 * {@code quantity}가 {@code seatIds}의 크기와 중복되는 건 사실이지만, 둘이 어긋난 요청은
 * 클라이언트가 좌석 선택과 매수 표시를 다르게 들고 있다는 뜻이라 서비스에서 400으로 끊는다.
 */
public record ReservationCreateRequest(
        @NotNull Long scheduleId,
        @NotNull Long ticketGradeId,
        @NotNull @Min(1) @Max(4) Integer quantity,
        @NotEmpty @Size(max = 4) List<Long> seatIds
) {
}
