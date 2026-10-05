package com.tikkit.api.domain.reservation.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 재고와 예약 상태를 바꾸는 모든 경로는 조건부 UPDATE를 쓴다 (Task 019).
 * 읽은 값을 애플리케이션이 다시 쓰지 않으므로 lost update와 상태 전이 덮어쓰기가 구조적으로 불가능하다.
 * 그 대신 <b>엔티티 필드를 고치면 안 된다</b> — 더티체킹이 메모리의 낡은 값으로 UPDATE를 또 발행해
 * 조건부 UPDATE를 무력화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    /** V3_2의 부분 유니크 인덱스 이름. 중복 선점 위반만 골라내는 데 쓴다. */
    private static final String UK_PENDING_HOLD = "uk_reservations_pending_member_grade";

    private final ReservationRepository reservationRepository;
    private final TicketGradeRepository ticketGradeRepository;
    private final MemberRepository memberRepository;
    private final ReservationNoGenerator reservationNoGenerator;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    /**
     * 예매 선점(PENDING 홀드)을 생성한다.
     * <p>
     * 재고 차감은 조건부 UPDATE({@code TicketGradeRepository.decreaseRemainingQuantity})가 담당한다.
     * 읽은 값을 애플리케이션이 다시 쓰지 않으므로 lost update가 구조적으로 불가능하다
     * (비관적·낙관적 락과의 비교: {@code docs/improvements/002-db-lock-comparison.md}).
     */
    @Transactional
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

        // 엔티티 필드를 건드리지 않는 것이 중요하다 — 영속 인스턴스가 더티가 되면 flush 시점에
        // Hibernate가 메모리의 낡은 값으로 UPDATE를 또 발행해 이 조건부 UPDATE를 덮어쓴다.
        if (ticketGradeRepository.decreaseRemainingQuantity(ticketGrade.getId(), request.quantity(), now) == 0) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }

        String reservationNo = reservationNoGenerator.generate(now);
        Reservation reservation = Reservation.createPending(
                reservationNo, memberRepository.getReferenceById(memberId), ticketGrade, request.quantity(), now);

        // 위 existsBy 가드는 check-then-insert라 동시 요청에 뚫린다. 최종 방어선은 V3_2의
        // 부분 유니크 인덱스이고, 그 위반만 골라 같은 409로 바꾼다 (Task 020).
        // Reservation의 PK가 IDENTITY라 save() 시점에 INSERT가 나가므로 여기서 잡을 수 있다 —
        // SEQUENCE였다면 커밋 시점에 터져서 서비스에서 못 잡고 500으로 떨어진다.
        try {
            reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicatePendingHold(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_PENDING_RESERVATION);
            }
            throw e;
        }

        return toResponse(reservation);
    }

    /**
     * 중복 선점 부분 유니크 인덱스 위반인지 제약명으로 가린다.
     * <p>
     * {@code GlobalExceptionHandler}에서 처리하지 않는 이유: 거기서는 어떤 제약인지 알 수 없어
     * {@code uk_reservations_reservation_no}(예약번호 시퀀스 한 바퀴)나
     * {@code uk_payments_reservation_id}(동시 이중 결제)까지 모두 409로 뭉개게 된다. 그쪽은 의미가
     * 다른 별개 과제로 남아 있다.
     */
    private boolean isDuplicatePendingHold(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return UK_PENDING_HOLD.equals(violation.getConstraintName());
            }
        }
        return false;
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
    @Transactional
    public ReservationResponse pay(Long memberId, Long id, PaymentRequest request) {
        Reservation reservation = reservationRepository.findMineWithDetails(id, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Instant now = Instant.now();
        reservation.validateConfirmable(now);   // PG를 부르기 전에 메모리 상태로 빠르게 끊는다

        String transactionKey =
                paymentGateway.approve(reservation.getReservationNo(), reservation.getTotalAmount(), request.method());

        // 승인을 기다린 동안 만료 배치나 취소가 먼저 상태를 바꿨을 수 있다. 메모리의 PENDING을 믿지 않고
        // DB 상태를 WHERE에 넣어 전이한다. 0행이면 우리가 늦은 것이다.
        if (reservationRepository.confirmIfPending(id, now) == 0) {
            compensateApprovedPayment(transactionKey);
            throw new BusinessException(resolveConfirmConflict(id));
        }

        paymentRepository.save(Payment.paid(reservation, request.method(), transactionKey, now));

        // 조건부 UPDATE로 바꿨으니 영속 인스턴스의 status/confirmedAt은 낡은 값이다 — 전이 결과를 직접 넘긴다.
        return toResponse(reservation, ReservationStatus.CONFIRMED, now, null);
    }

    /**
     * 승인은 됐지만 확정이 실패한 경우의 보상 환불.
     * <p>
     * 환불이 또 실패해도 원래 예외(만료·상태 충돌)를 가려서는 안 되므로 삼켜서 로그만 남긴다.
     * 이 트랜잭션은 곧 롤백되므로 실패 사실을 DB에 남길 수 없다 — 영속적인 보상(아웃박스 + 정산 배치)은
     * 범위 밖이다 (README "알려진 한계" 참조).
     */
    private void compensateApprovedPayment(String transactionKey) {
        try {
            paymentGateway.refund(transactionKey);
        } catch (RuntimeException e) {
            log.error("확정 실패 후 보상 환불이 실패했습니다. 수동 정산이 필요합니다. transactionKey={}",
                    transactionKey, e);
        }
    }

    /**
     * 확정 조건부 UPDATE가 0행일 때 실제 DB 상태로 에러 코드를 결정한다.
     * EXPIRED(배치가 선수) 또는 PENDING({@code expires_at}이 지남)이면 만료로, 그 외(CONFIRMED/CANCELLED)는
     * 상태 전이 위반으로 응답한다 — 기존 에러 계약과 같다.
     */
    private ErrorCode resolveConfirmConflict(Long id) {
        ReservationStatus actual = reservationRepository.findStatusById(id).orElse(null);
        return (actual == ReservationStatus.EXPIRED || actual == ReservationStatus.PENDING)
                ? ErrorCode.RESERVATION_EXPIRED
                : ErrorCode.INVALID_STATUS_TRANSITION;
    }

    /**
     * 예매를 취소한다. PENDING은 시점 제한 없이, CONFIRMED는 공연 24시간 전까지만 가능하다 (docs/PRD.md 참조).
     * CONFIRMED 취소는 결제를 환불 처리하고, 두 상태 모두 취소 시 잔여 수량을 복원한다.
     */
    @Transactional
    public ReservationResponse cancel(Long memberId, Long id) {
        Reservation reservation = reservationRepository.findMineWithDetails(id, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Instant now = Instant.now();
        ReservationStatus observed = reservation.getStatus();
        boolean isConfirmed = observed == ReservationStatus.CONFIRMED;
        if (isConfirmed && reservation.isCancelDeadlinePassed(now)) {
            throw new BusinessException(ErrorCode.CANCEL_DEADLINE_PASSED);
        }
        reservation.validateCancellable();

        // 읽은 상태를 WHERE에 넣은 조건부 전이. 1행을 바꾼 트랜잭션만 아래 환불·재고 복원을 수행한다.
        // 만료 배치가 먼저 EXPIRED로 바꿨으면 0행이 되어 여기서 끊기므로, 재고 이중 복원이 일어날 수 없다.
        if (reservationRepository.cancelIfStatus(id, observed, now) == 0) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }

        // 환불을 전이 뒤로 옮겼다 — 전이가 실패했는데 환불부터 하면 돈만 돌려주고 취소는 안 된 상태가 된다.
        if (isConfirmed) {
            Payment payment = paymentRepository.findByReservationId(reservation.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "CONFIRMED 예약(id=" + reservation.getId() + ")에 결제 내역이 없습니다."));
            paymentGateway.refund(payment.getTransactionKey());
            payment.refund(now);
        }

        int restored = ticketGradeRepository.increaseRemainingQuantity(
                reservation.getTicketGrade().getId(), reservation.getQuantity(), now);
        if (restored == 0) {
            // 상한을 넘기는 복원 = 이미 누군가 복원했다는 뜻. 위 조건부 전이를 통과했으므로 도달할 수 없다.
            // 조용히 넘기면 재고가 영구히 어긋나므로 롤백시켜 전이까지 되돌린다.
            throw new IllegalStateException(
                    "예약(id=%d) 취소 중 재고 복원이 상한을 넘었습니다 — 이중 복원 가능성".formatted(id));
        }

        return toResponse(reservation, ReservationStatus.CANCELLED, reservation.getConfirmedAt(), now);
    }

    /**
     * 조건부 UPDATE로 상태를 바꾼 뒤의 응답을 만든다.
     * <p>
     * 벌크 UPDATE는 1차 캐시를 갱신하지 않으므로 영속 인스턴스의 {@code status}·{@code confirmedAt}·
     * {@code cancelledAt}은 낡은 값이다. 엔티티에서 읽으면 안 되고 전이 결과를 인자로 받아야 한다.
     */
    private ReservationResponse toResponse(Reservation reservation, ReservationStatus status,
                                           Instant confirmedAt, Instant cancelledAt) {
        return new ReservationResponse(
                reservation.getId(), reservation.getReservationNo(), status,
                reservation.getExpiresAt(), confirmedAt, cancelledAt);
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
