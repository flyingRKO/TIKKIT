package com.tikkit.api.domain.reservation.dto;

import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 예매 상세. 결제 화면과 예매 내역 상세가 같은 응답을 쓴다.
 * <p>
 * {@code seats}는 Task 023에서 추가했다 — 사용자가 배치도에서 자리를 고르게 되면서
 * "내가 고른 자리"를 다시 보여줄 곳이 필요해졌다. 목록 응답({@code ReservationSummaryResponse})에는
 * 넣지 않는다. 목록 N건마다 좌석 조인이 돌아 N+1이 되고, 목록에서는 매수만 보여주면 된다.
 */
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
        List<ReservationSeatResponse> seats,
        PaymentResponse payment
) {
}
