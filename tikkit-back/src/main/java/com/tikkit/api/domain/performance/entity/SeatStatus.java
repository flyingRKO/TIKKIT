package com.tikkit.api.domain.performance.entity;

/**
 * 회차별 좌석의 판매 상태.
 * <p>
 * {@code AVAILABLE}이면 점유자가 없고, {@code HELD}(선점) / {@code SOLD}(결제 완료)면 반드시 있다.
 * 이 불변식은 {@code ck_schedule_seats_status_holder}가 DB에서 강제한다.
 */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    SOLD
}
