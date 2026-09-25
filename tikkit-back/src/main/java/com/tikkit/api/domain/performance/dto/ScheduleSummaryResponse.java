package com.tikkit.api.domain.performance.dto;

import java.time.Instant;

public record ScheduleSummaryResponse(
        Long id,
        Instant showAt,
        Instant bookingOpenAt,
        Instant bookingCloseAt
) {
}
