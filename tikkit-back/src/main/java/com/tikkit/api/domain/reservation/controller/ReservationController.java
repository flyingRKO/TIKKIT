package com.tikkit.api.domain.reservation.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.common.response.PageResponse;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.reservation.dto.PaymentRequest;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationDetailResponse;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Tag(name = "Reservation", description = "예매 선점·조회·결제·취소")
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    @Operation(summary = "예매 선점 (10분 홀드)")
    @PostMapping
    public ResponseEntity<ApiResponse<ReservationResponse>> create(@RequestBody @Valid ReservationCreateRequest request) {
        // TODO(Task 012): 판매 기간·잔여 수량 검증 후 실제 선점 로직 연결
        ReservationResponse dummy = new ReservationResponse(
                1L, "TK260925-000001", ReservationStatus.PENDING,
                Instant.now().plus(10, ChronoUnit.MINUTES), null, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(dummy));
    }

    @Operation(summary = "내 예매 목록 조회")
    @GetMapping
    public ApiResponse<PageResponse<ReservationSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // TODO(Task 013): 로그인한 회원 본인 예약만 조회하는 실제 로직 연결
        ReservationSummaryResponse dummy = new ReservationSummaryResponse(
                1L, "TK260925-000001", "판타지아 오케스트라", Instant.now().plus(20, ChronoUnit.DAYS),
                Grade.VIP, 2, new BigDecimal("300000"), ReservationStatus.PENDING);
        PageImpl<ReservationSummaryResponse> dummyPage =
                new PageImpl<>(List.of(dummy), PageRequest.of(page, size), 1);
        return ApiResponse.success(new PageResponse<>(dummyPage));
    }

    @Operation(summary = "예매 상세 조회 (소유자 검증)")
    @GetMapping("/{id}")
    public ApiResponse<ReservationDetailResponse> detail(@PathVariable Long id) {
        // TODO(Task 013): 소유자 검증 포함 실제 조회 로직 연결. 타인 소유 예약은 403이 아닌 404 반환
        ReservationDetailResponse dummy = new ReservationDetailResponse(
                id, "TK260925-000001", "판타지아 오케스트라", Instant.now().plus(20, ChronoUnit.DAYS),
                Grade.VIP, 2, new BigDecimal("150000"), new BigDecimal("300000"),
                ReservationStatus.PENDING, Instant.now().plus(10, ChronoUnit.MINUTES), null, null, null);
        return ApiResponse.success(dummy);
    }

    @Operation(summary = "모의 결제")
    @PostMapping("/{id}/payments")
    public ApiResponse<ReservationResponse> pay(@PathVariable Long id, @RequestBody @Valid PaymentRequest request) {
        // TODO(Task 013): 모의 결제 처리 후 PENDING -> CONFIRMED 전이 로직 연결
        ReservationResponse dummy = new ReservationResponse(
                id, "TK260925-000001", ReservationStatus.CONFIRMED, null, Instant.now(), null);
        return ApiResponse.success(dummy);
    }

    @Operation(summary = "예매 취소")
    @PostMapping("/{id}/cancel")
    public ApiResponse<ReservationResponse> cancel(@PathVariable Long id) {
        // TODO(Task 013): 재고 복원 포함 취소 로직 연결
        ReservationResponse dummy = new ReservationResponse(
                id, "TK260925-000001", ReservationStatus.CANCELLED, null, null, Instant.now());
        return ApiResponse.success(dummy);
    }
}
