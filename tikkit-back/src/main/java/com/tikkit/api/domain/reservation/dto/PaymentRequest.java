package com.tikkit.api.domain.reservation.dto;

import com.tikkit.api.domain.payment.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;

public record PaymentRequest(
        @NotNull PaymentMethod method
) {
}
