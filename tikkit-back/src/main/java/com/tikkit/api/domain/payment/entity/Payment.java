package com.tikkit.api.domain.payment.entity;

import com.tikkit.api.common.entity.BaseTimeEntity;
import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.reservation.entity.Reservation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Entity
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, length = 64)
    private String transactionKey;

    private Instant paidAt;

    private Instant refundedAt;

    @Builder
    private Payment(Reservation reservation, BigDecimal amount, PaymentMethod method, PaymentStatus status,
                     String transactionKey, Instant paidAt, Instant refundedAt) {
        this.reservation = reservation;
        this.amount = amount;
        this.method = method;
        this.status = status;
        this.transactionKey = transactionKey;
        this.paidAt = paidAt;
        this.refundedAt = refundedAt;
    }

    /** 결제 승인 성공 직후 PAID 상태의 결제 내역을 생성한다. transactionKey는 PaymentGateway 응답값이다. */
    public static Payment paid(Reservation reservation, PaymentMethod method, String transactionKey, Instant now) {
        return Payment.builder()
                .reservation(reservation)
                .amount(reservation.getTotalAmount())
                .method(method)
                .status(PaymentStatus.PAID)
                .transactionKey(transactionKey)
                .paidAt(now)
                .build();
    }

    /** PAID -> REFUNDED 전이. 예약 취소 시 PaymentGateway 환불 호출 이후에 호출한다. */
    public void refund(Instant now) {
        if (status != PaymentStatus.PAID) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = now;
    }
}
