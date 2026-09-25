package com.tikkit.api.domain.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReservationCreateRequest(
        @NotNull Long scheduleId,
        @NotNull Long ticketGradeId,
        @NotNull @Min(1) @Max(4) Integer quantity
) {
}
