package com.tikkit.api.domain.reservation.entity;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.entity.Schedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private Reservation reservation(ReservationStatus status, Instant expiresAt, Instant showAt) {
        Schedule schedule = Schedule.builder()
                .showAt(showAt)
                .bookingOpenAt(showAt.minus(30, ChronoUnit.DAYS))
                .bookingCloseAt(showAt.minus(1, ChronoUnit.HOURS))
                .build();
        return Reservation.builder()
                .reservationNo("TK260101-000001")
                .schedule(schedule)
                .quantity(2)
                .unitPrice(new BigDecimal("150000"))
                .totalAmount(new BigDecimal("300000"))
                .status(status)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("만료 시각과 정확히 같으면 만료된 것으로 본다 (만료 시각 포함)")
    void 만료_시각_경계_포함() {
        // given
        Instant expiresAt = Instant.now();
        Reservation reservation = reservation(ReservationStatus.PENDING, expiresAt, expiresAt.plus(1, ChronoUnit.DAYS));

        // when & then
        assertThat(reservation.isExpired(expiresAt)).isTrue();
    }

    @Test
    @DisplayName("만료 시각 이전이면 만료되지 않았다")
    void 만료_시각_이전() {
        // given
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.MINUTES);
        Reservation reservation = reservation(ReservationStatus.PENDING, expiresAt, expiresAt.plus(1, ChronoUnit.DAYS));

        // when & then
        assertThat(reservation.isExpired(Instant.now())).isFalse();
    }

    // Task 019부터 상태 전이는 엔티티가 아니라 조건부 UPDATE(ReservationRepository)가 수행한다.
    // 엔티티에는 "전이 가능한 상태인가"를 판단하는 검증만 남아 있어서, 아래 테스트들도 검증 결과만 확인한다.

    @Test
    @DisplayName("선점 시간이 남은 PENDING 예약은 확정 가능 검증을 통과한다")
    void 확정_가능_검증_통과() {
        // given
        Reservation reservation = reservation(ReservationStatus.PENDING, Instant.now().plus(5, ChronoUnit.MINUTES),
                Instant.now().plus(30, ChronoUnit.DAYS));

        // when & then
        assertThatNoException().isThrownBy(() -> reservation.validateConfirmable(Instant.now()));
    }

    @Test
    @DisplayName("PENDING이 아닌 예약은 확정할 수 없다")
    void 확정_검증_실패_잘못된_상태() {
        // given
        Reservation reservation = reservation(ReservationStatus.CONFIRMED, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));

        // when & then
        assertThatThrownBy(() -> reservation.validateConfirmable(Instant.now()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("선점 시간이 지난 PENDING과 이미 EXPIRED인 예약은 RESERVATION_EXPIRED로 막는다")
    void 확정_검증_실패_만료() {
        // given
        Instant now = Instant.now();
        Reservation expiredHold = reservation(ReservationStatus.PENDING, now.minus(1, ChronoUnit.MINUTES),
                now.plus(30, ChronoUnit.DAYS));
        Reservation alreadyExpired = reservation(ReservationStatus.EXPIRED, now.minus(1, ChronoUnit.MINUTES),
                now.plus(30, ChronoUnit.DAYS));

        // when & then
        assertThatThrownBy(() -> expiredHold.validateConfirmable(now))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_EXPIRED);
        assertThatThrownBy(() -> alreadyExpired.validateConfirmable(now))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_EXPIRED);
    }

    @Test
    @DisplayName("만료 시각이 지난 PENDING 예약도 취소 가능 검증을 통과한다")
    void 만료된_PENDING_취소_허용() {
        // given
        Instant now = Instant.now();
        Reservation reservation = reservation(ReservationStatus.PENDING, now.minus(1, ChronoUnit.MINUTES),
                now.plus(30, ChronoUnit.DAYS));

        // when & then
        assertThatNoException().isThrownBy(reservation::validateCancellable);
    }

    @Test
    @DisplayName("CONFIRMED 예약도 취소 가능 검증을 통과한다")
    void CONFIRMED_취소_허용() {
        // given
        Instant now = Instant.now();
        Reservation reservation = reservation(ReservationStatus.CONFIRMED, now, now.plus(30, ChronoUnit.DAYS));

        // when & then
        assertThatNoException().isThrownBy(reservation::validateCancellable);
    }

    @Test
    @DisplayName("이미 CANCELLED거나 EXPIRED인 예약은 다시 취소할 수 없다")
    void 이미_종결된_예약_취소_실패() {
        // given
        Reservation cancelled = reservation(ReservationStatus.CANCELLED, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        Reservation expired = reservation(ReservationStatus.EXPIRED, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));

        // when & then
        assertThatThrownBy(cancelled::validateCancellable)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
        assertThatThrownBy(expired::validateCancellable)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("공연 시작 24시간 전과 정확히 같으면 취소 마감이 지난 것으로 본다 (마감 시각 포함)")
    void 취소_마감_경계_포함() {
        // given
        Instant showAt = Instant.now().plus(24, ChronoUnit.HOURS);
        Reservation reservation = reservation(ReservationStatus.CONFIRMED, Instant.now(), showAt);
        Instant deadline = showAt.minus(24, ChronoUnit.HOURS);

        // when & then
        assertThat(reservation.isCancelDeadlinePassed(deadline)).isTrue();
    }

    @Test
    @DisplayName("공연 시작 24시간 전보다 이전이면 취소할 수 있다")
    void 취소_마감_이전() {
        // given
        Instant showAt = Instant.now().plus(30, ChronoUnit.DAYS);
        Reservation reservation = reservation(ReservationStatus.CONFIRMED, Instant.now(), showAt);

        // when & then
        assertThat(reservation.isCancelDeadlinePassed(Instant.now())).isFalse();
    }
}
