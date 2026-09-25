package com.tikkit.api.domain.performance.dto;

import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;

import java.time.LocalDate;

public record PerformanceSummaryResponse(
        Long id,
        String title,
        PerformanceCategory category,
        String posterUrl,
        String venueName,
        PerformanceStatus status,
        LocalDate startDate,
        LocalDate endDate
) {
}
