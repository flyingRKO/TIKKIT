package com.tikkit.api.domain.reservation.dto;

import com.tikkit.api.domain.reservation.entity.ReservationStatus;

import java.time.Instant;

public record ReservationResponse(
        Long id,
        String reservationNo,
        ReservationStatus status,
        Instant expiresAt,
        Instant confirmedAt,
        Instant cancelledAt
) {
}
