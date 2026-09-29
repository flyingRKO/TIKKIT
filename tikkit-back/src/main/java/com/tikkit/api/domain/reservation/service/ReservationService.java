package com.tikkit.api.domain.reservation.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.payment.entity.Payment;
import com.tikkit.api.domain.payment.gateway.PaymentGateway;
import com.tikkit.api.domain.payment.repository.PaymentRepository;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.reservation.dto.PaymentRequest;
import com.tikkit.api.domain.reservation.dto.PaymentResponse;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationDetailResponse;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final TicketGradeRepository ticketGradeRepository;
    private final MemberRepository memberRepository;
    private final ReservationNoGenerator reservationNoGenerator;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    /**
     * 예매 선점(PENDING 홀드)을 생성한다.
     * 동시성 미보장 — 재고 차감(TicketGrade.decreaseRemaining)이 단순 읽기-쓰기 방식이라 동시 요청이 몰리면
     * 초과 판매가 날 수 있다. Phase 5(Task 018~020)에서 조건부 UPDATE로 개선한다.
     */
    public ReservationResponse create(Long memberId, ReservationCreateRequest request) {
        TicketGrade ticketGrade = ticketGradeRepository.findById(request.ticketGradeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Schedule schedule = ticketGrade.getSchedule();
        if (!schedule.getId().equals(request.scheduleId())) {
            // 요청한 회차와 등급이 서로 다른 회차를 가리키는 경우 — 존재하지 않는 조합으로 취급한다.
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        Instant now = Instant.now();
        if (!schedule.isBookingOpen(now)) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_OPEN);
        }

        // 같은 회원이 같은 등급에 이미 선점 중인 PENDING 예약이 있으면 중복 선점을 막는다.
        if (reservationRepository.existsByMemberIdAndTicketGradeIdAndStatus(
                memberId, ticketGrade.getId(), ReservationStatus.PENDING)) {
            throw new BusinessException(ErrorCode.DUPLICATE_PENDING_RESERVATION);
        }

        ticketGrade.decreaseRemaining(request.quantity());

        String reservationNo = reservationNoGenerator.generate(now);
        Reservation reservation = Reservation.createPending(
                reservationNo, memberRepository.getReferenceById(memberId), ticketGrade, request.quantity(), now);
        reservationRepository.save(reservation);

        return toResponse(reservation);
    }

    /** 내 예매 목록을 상태(선택)로 필터링해 최신순으로 조회한다. */
    @Transactional(readOnly = true)
    public Page<ReservationSummaryResponse> getMyReservations(Long memberId, String status, int page, int size) {
        ReservationStatus statusFilter = parseStatus(status);
        return reservationRepository.searchMine(memberId, statusFilter, PageRequest.of(page, size));
    }

    /**
     * 내 예매 상세를 소유자 검증과 함께 조회한다.
     * 다른 회원 소유거나 존재하지 않으면 404 NOT_FOUND — 존재 여부 자체를 노출하지 않기 위함이다 (docs/PRD.md 참조).
     */
    @Transactional(readOnly = true)
    public ReservationDetailResponse getMyReservation(Long memberId, Long id) {
        Reservation reservation = reservationRepository.findMineWithDetails(id, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        PaymentResponse payment = paymentRepository.findByReservationId(reservation.getId())
                .map(this::toPaymentResponse)
                .orElse(null);

        return new ReservationDetailResponse(
                reservation.getId(), reservation.getReservationNo(),
                reservation.getSchedule().getPerformance().getTitle(), reservation.getSchedule().getShowAt(),
                reservation.getTicketGrade().getGrade(), reservation.getQuantity(), reservation.getUnitPrice(),
                reservation.getTotalAmount(), reservation.getStatus(), reservation.getExpiresAt(),
                reservation.getConfirmedAt(), reservation.getCancelledAt(), payment);
    }

    /**
     * 모의 결제를 처리해 PENDING -> CONFIRMED로 확정한다.
     * PaymentGateway 호출을 이 트랜잭션 안에서 하는 이유: 지금은 MockPaymentGateway뿐이라 즉시 응답하므로
     * 커넥션을 오래 잡아두는 문제가 없다. 실제 PG(네트워크 I/O)로 바꿀 때는 승인 호출을 트랜잭션 밖으로 빼고,
     * 짧은 트랜잭션으로 상태를 재확인 후 확정하는 구조로 바꿔야 한다 (README "알려진 한계" 참조).
     */
    public ReservationResponse pay(Long memberId, Long id, PaymentRequest request) {
        Reservation reservation = reservationRepository.findMineWithDetails(id, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Instant now = Instant.now();
        boolean alreadyExpired = reservation.getStatus() == ReservationStatus.EXPIRED
                || (reservation.getStatus() == ReservationStatus.PENDING && reservation.isExpired(now));
        if (alreadyExpired) {
            throw new BusinessException(ErrorCode.RESERVATION_EXPIRED);
        }
        // 그 외 PENDING이 아닌 상태(CONFIRMED/CANCELLED)는 confirm()이 INVALID_STATUS_TRANSITION으로 막는다

        String transactionKey =
                paymentGateway.approve(reservation.getReservationNo(), reservation.getTotalAmount(), request.method());

        reservation.confirm(now);
        paymentRepository.save(Payment.paid(reservation, request.method(), transactionKey, now));

        return toResponse(reservation);
    }

    /**
     * 예매를 취소한다. PENDING은 시점 제한 없이, CONFIRMED는 공연 24시간 전까지만 가능하다 (docs/PRD.md 참조).
     * CONFIRMED 취소는 결제를 환불 처리하고, 두 상태 모두 취소 시 잔여 수량을 복원한다.
     */
    public ReservationResponse cancel(Long memberId, Long id) {
        Reservation reservation = reservationRepository.findMineWithDetails(id, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Instant now = Instant.now();
        boolean isConfirmed = reservation.getStatus() == ReservationStatus.CONFIRMED;
        if (isConfirmed && reservation.isCancelDeadlinePassed(now)) {
            throw new BusinessException(ErrorCode.CANCEL_DEADLINE_PASSED);
        }
        if (isConfirmed) {
            Payment payment = paymentRepository.findByReservationId(reservation.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "CONFIRMED 예약(id=" + reservation.getId() + ")에 결제 내역이 없습니다."));
            paymentGateway.refund(payment.getTransactionKey());
            payment.refund(now);
        }

        // PENDING/CONFIRMED가 아니면 cancel()이 INVALID_STATUS_TRANSITION으로 막는다
        reservation.cancel(now);
        reservation.getTicketGrade().increaseRemaining(reservation.getQuantity());

        return toResponse(reservation);
    }

    private ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(), reservation.getReservationNo(), reservation.getStatus(),
                reservation.getExpiresAt(), reservation.getConfirmedAt(), reservation.getCancelledAt());
    }

    private PaymentResponse toPaymentResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(), payment.getMethod(), payment.getStatus(), payment.getTransactionKey(), payment.getPaidAt());
    }

    /**
     * 비어있으면 필터 없음(null)으로 취급하고, 값이 있는데 enum과 매칭되지 않으면 400으로 응답한다.
     * PerformanceService.parseEnum과 같은 방식이다 — 도메인이 다른 소규모 로직이라 공통 유틸로 빼지 않았다.
     */
    private ReservationStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ReservationStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "유효하지 않은 status 값입니다: " + value);
        }
    }
}
