package com.tikkit.api.domain.reservation.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.common.response.PageResponse;
import com.tikkit.api.domain.reservation.dto.PaymentRequest;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationDetailResponse;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
import com.tikkit.api.domain.queue.service.QueueGateway;
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

    /**
     * 대기열 입장권 가드 (Task 031에서 서비스에, Task 033에서 여기로).
     *
     * <h2>왜 서비스가 아니라 컨트롤러인가</h2>
     * {@code ReservationService.create()}가 {@code @Transactional}이어서, 그 안에서 거절하면
     * <b>거절 한 건이 Hikari 커넥션 한 개를 잡고 나서 던진다</b> — SQL을 한 줄도 돌리지 않는데도
     * 트랜잭션 시작 시점에 획득하기 때문이다. 전원이 403을 받는 구간에서 재보니
     * {@code hikaricp_connections_acquire_seconds_count}가 <b>요청당 1.01개</b> 늘었다.
     * 대기열의 목적이 DB를 지키는 것인데 거절이 그 자원을 그대로 소모하고 있었다.
     *
     * <h2>인터셉터는 여전히 못 쓴다</h2>
     * {@code scheduleId}가 요청 본문에 있어서 인터셉터가 읽으면 {@code @RequestBody} 역직렬화가
     * 빈 스트림을 만난다. 컨트롤러에는 이미 역직렬화된 본문이 있으니 그 제약이 사라진다.
     * 좌석 조회를 {@code QueuePassInterceptor}가 막는 것과 같은 웹 계층이 되는 셈이다.
     *
     * <h2>꺼져 있으면 아무 일도 안 한다</h2>
     * {@code tikkit.queue.enabled=false}면 {@code DisabledQueueGateway}가 주입되고, 켜져 있어도
     * 회차 플래그가 false면 즉시 통과한다 — 둘 다 Redis를 건드리지 않는다.
     */
    private final QueueGateway queueGateway;

    @Operation(summary = "예매 선점 (10분 홀드)")
    @PostMapping
    public ResponseEntity<ApiResponse<ReservationResponse>> create(@AuthenticationPrincipal MemberDetails memberDetails,
                                                                     @RequestBody @Valid ReservationCreateRequest request) {
        // 트랜잭션 **밖에서** 거절한다. 위 필드 Javadoc 참조.
        queueGateway.requireAdmitted(request.scheduleId(), memberDetails.getMemberId());

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
