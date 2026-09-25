package com.tikkit.api.domain.reservation.dto;

import com.tikkit.api.domain.payment.entity.PaymentMethod;
import com.tikkit.api.domain.payment.entity.PaymentStatus;

import java.time.Instant;

public record PaymentResponse(
        Long id,
        PaymentMethod method,
        PaymentStatus status,
        String transactionKey,
        Instant paidAt
) {
}
