package com.tikkit.api.domain.reservation.dto;

/**
 * 예약이 받은 좌석 한 자리. "구역 / 열 / 번" 국내 예매처 관행을 그대로 내려준다 (docs/ERD.md 2절).
 * <p>
 * {@code price}를 넣지 않은 이유: 한 예약 = 한 등급 정책이라 좌석 단가가 전부 같고,
 * {@code ReservationDetailResponse.unitPrice}에 이미 있다. 좌석마다 같은 값을 반복하면
 * 응답만 커지고 "좌석별 차등 가격이 있다"는 오해를 준다.
 * <p>
 * {@code schedule_seats.id}도 내려주지 않는다. 화면이 자리 이름만 보여주고, 선점이 끝난 뒤에는
 * 그 id로 할 수 있는 일이 없다.
 */
public record ReservationSeatResponse(
        String section,
        String rowLabel,
        Integer seatNumber
) {
}
