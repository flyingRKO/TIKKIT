package com.tikkit.api.domain.performance.dto;

import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;

import java.util.List;

public record PerformanceDetailResponse(
        Long id,
        String title,
        PerformanceCategory category,
        String description,
        String posterUrl,
        String venueName,
        String venueAddress,
        Integer runningMinutes,
        String ageRating,
        PerformanceStatus status,
        List<ScheduleSummaryResponse> schedules
) {
}
