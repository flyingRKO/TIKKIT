package com.tikkit.api.domain.performance.dto;

import com.tikkit.api.domain.performance.entity.Grade;

import java.math.BigDecimal;

public record TicketGradeResponse(
        Long id,
        Grade grade,
        BigDecimal price,
        Integer remainingQuantity
) {
}
