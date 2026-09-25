package com.tikkit.api.domain.reservation.dto;

import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ReservationSummaryResponse(
        Long id,
        String reservationNo,
        String performanceTitle,
        Instant scheduleShowAt,
        Grade grade,
        Integer quantity,
        BigDecimal totalAmount,
        ReservationStatus status
) {
}
