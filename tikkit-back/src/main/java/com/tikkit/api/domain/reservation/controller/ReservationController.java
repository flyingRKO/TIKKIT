package com.tikkit.api.domain.reservation.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.common.response.PageResponse;
import com.tikkit.api.domain.reservation.dto.PaymentRequest;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationDetailResponse;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
import com.tikkit.api.domain.reservation.service.ReservationService;
import com.tikkit.api.security.MemberDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reservation", description = "예매 선점·조회·결제·취소")
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(summary = "예매 선점 (10분 홀드)")
    @PostMapping
    public ResponseEntity<ApiResponse<ReservationResponse>> create(@AuthenticationPrincipal MemberDetails memberDetails,
                                                                     @RequestBody @Valid ReservationCreateRequest request) {
        ReservationResponse response = reservationService.create(memberDetails.getMemberId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Operation(summary = "내 예매 목록 조회")
    @GetMapping
    public ApiResponse<PageResponse<ReservationSummaryResponse>> list(
            @AuthenticationPrincipal MemberDetails memberDetails,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(new PageResponse<>(
                reservationService.getMyReservations(memberDetails.getMemberId(), status, page, size)));
    }

    @Operation(summary = "예매 상세 조회 (소유자 검증)")
    @GetMapping("/{id}")
    public ApiResponse<ReservationDetailResponse> detail(@AuthenticationPrincipal MemberDetails memberDetails,
                                                           @PathVariable Long id) {
        return ApiResponse.success(reservationService.getMyReservation(memberDetails.getMemberId(), id));
    }

    @Operation(summary = "모의 결제")
    @PostMapping("/{id}/payments")
    public ApiResponse<ReservationResponse> pay(@AuthenticationPrincipal MemberDetails memberDetails,
                                                 @PathVariable Long id, @RequestBody @Valid PaymentRequest request) {
        return ApiResponse.success(reservationService.pay(memberDetails.getMemberId(), id, request));
    }

    @Operation(summary = "예매 취소")
    @PostMapping("/{id}/cancel")
    public ApiResponse<ReservationResponse> cancel(@AuthenticationPrincipal MemberDetails memberDetails,
                                                     @PathVariable Long id) {
        return ApiResponse.success(reservationService.cancel(memberDetails.getMemberId(), id));
    }
}
