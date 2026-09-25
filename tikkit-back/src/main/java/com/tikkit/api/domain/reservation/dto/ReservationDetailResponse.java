package com.tikkit.api.domain.reservation.dto;

import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ReservationDetailResponse(
        Long id,
        String reservationNo,
        String performanceTitle,
        Instant scheduleShowAt,
        Grade grade,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        ReservationStatus status,
        Instant expiresAt,
        Instant confirmedAt,
        Instant cancelledAt,
        PaymentResponse payment
) {
}
