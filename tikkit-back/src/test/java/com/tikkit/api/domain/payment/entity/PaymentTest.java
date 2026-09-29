package com.tikkit.api.domain.payment.entity;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private Reservation reservation() {
        return Reservation.builder()
                .reservationNo("TK260101-000001")
                .quantity(2)
                .unitPrice(new BigDecimal("150000"))
                .totalAmount(new BigDecimal("300000"))
                .status(ReservationStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("결제 승인 성공 시 PAID 상태로 생성되고 예약 총액을 그대로 담는다")
    void 결제_생성() {
        // given
        Instant now = Instant.now();

        // when
        Payment payment = Payment.paid(reservation(), PaymentMethod.CARD, "mock-tx-key", now);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getAmount()).isEqualTo(new BigDecimal("300000"));
        assertThat(payment.getTransactionKey()).isEqualTo("mock-tx-key");
        assertThat(payment.getPaidAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("PAID 상태의 결제를 환불하면 REFUNDED로 바뀌고 refundedAt이 채워진다")
    void 환불_성공() {
        // given
        Payment payment = Payment.paid(reservation(), PaymentMethod.CARD, "mock-tx-key", Instant.now());
        Instant now = Instant.now();

        // when
        payment.refund(now);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("이미 REFUNDED인 결제는 다시 환불할 수 없다")
    void 환불_실패_이미_환불됨() {
        // given
        Payment payment = Payment.paid(reservation(), PaymentMethod.CARD, "mock-tx-key", Instant.now());
        payment.refund(Instant.now());

        // when & then
        assertThatThrownBy(() -> payment.refund(Instant.now()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
    }
}
