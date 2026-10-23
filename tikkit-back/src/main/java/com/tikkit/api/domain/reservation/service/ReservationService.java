package com.tikkit.api.domain.reservation.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.payment.entity.Payment;
import com.tikkit.api.domain.payment.gateway.PaymentGateway;
import com.tikkit.api.domain.queue.service.QueueGateway;
import com.tikkit.api.domain.payment.repository.PaymentRepository;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.reservation.dto.PaymentRequest;
import com.tikkit.api.domain.reservation.dto.PaymentResponse;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationDetailResponse;
import com.tikkit.api.domain.reservation.dto.ReservationSeatResponse;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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

    /**
     * 예매 선점 시도 카운터 (Task 025). Prometheus에서는
     * {@code tikkit_reservation_attempts_total{result="failure",reason="SOLD_OUT"}}로 보인다.
     * <p>
     * 카운터를 success/failure 두 개로 쪼개지 않은 이유: 한 쿼리에서 {@code by (result)}로 묶거나
     * 실패율을 계산할 수 없게 된다.
     */
    private static final String ATTEMPTS_METRIC = "tikkit.reservation.attempts";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";
    private static final String REASON_NONE = "none";
    private static final String REASON_UNEXPECTED = "UNEXPECTED";

    /** {@code create}가 실제로 던지는 ErrorCode 목록. 기동 시 0으로 미리 등록하는 데 쓴다. */
    private static final List<ErrorCode> CREATE_FAILURE_CODES = List.of(
            ErrorCode.NOT_FOUND,
            ErrorCode.BOOKING_NOT_OPEN,
            ErrorCode.VALIDATION_ERROR,
            ErrorCode.DUPLICATE_PENDING_RESERVATION,
            ErrorCode.SOLD_OUT);

    private final ReservationRepository reservationRepository;
    private final TicketGradeRepository ticketGradeRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final MemberRepository memberRepository;
    private final ReservationNoGenerator reservationNoGenerator;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final MeterRegistry meterRegistry;
    private final QueueGateway queueGateway;

    /**
     * 예매 카운터를 0으로 미리 등록한다.
     * <p>
     * 한 번도 증가하지 않은 Counter는 {@code /actuator/prometheus}에 시리즈 자체가 나오지 않는다.
     * 그러면 갓 띄운 스택의 Grafana 패널이 "No data"가 되어 <b>실패가 0인 것과 계측이 없는 것을
     * 구분할 수 없다.</b>
     */
    @PostConstruct
    void registerAttemptCounters() {
        attemptCounter(RESULT_SUCCESS, REASON_NONE);
        for (ErrorCode code : CREATE_FAILURE_CODES) {
            attemptCounter(RESULT_FAILURE, code.name());
        }
        attemptCounter(RESULT_FAILURE, REASON_UNEXPECTED);
    }

    /**
     * 성공에도 {@code reason="none"}을 붙인다 — Micrometer는 같은 이름에 태그 <b>키 집합</b>이
     * 다른 미터를 거부하므로, 성공만 reason을 빼면 두 번째 등록에서 예외가 난다.
     * <p>
     * reason을 ErrorCode 이름으로 두는 이유: enum이라 값이 유계다. 사용자 입력을 태그로 쓰면
     * 시리즈가 무한히 늘어나 Prometheus가 죽는다.
     */
    private Counter attemptCounter(String result, String reason) {
        return meterRegistry.counter(ATTEMPTS_METRIC, "result", result, "reason", reason);
    }

    /**
     * 예매 선점을 생성하고 성공·실패를 계측한다 (Task 025).
     * <p>
     * 계측을 서비스 안에서 하는 이유: {@code @Counted} AOP는 예외 클래스명만 태그로 붙이는데
     * 이 흐름의 실패는 전부 {@code BusinessException} 한 종류라 SOLD_OUT과
     * DUPLICATE_PENDING_RESERVATION을 구분할 수 없다. 그 구분이 오픈런 분석의 핵심이다.
     * <p>
     * <b>한계</b>: 성공 카운트가 트랜잭션 안에서 증가하므로, 커밋 시점에 롤백되면(드묾) 성공이
     * 과대 집계된다. 또 컨트롤러 단에서 400/401로 끊긴 요청은 여기 닿지 않아 잡히지 않는다 —
     * 그건 {@code http_server_requests}의 status별 지표가 보여준다.
     */
    @Transactional
    public ReservationResponse create(Long memberId, ReservationCreateRequest request) {
        // 대기열 입장권 검증 (Task 031).
        //
        // 인터셉터가 아니라 여기서 부르는 이유: scheduleId가 **요청 본문**에 있다. 인터셉터가
        // 본문을 읽으면 @RequestBody 역직렬화가 빈 스트림을 만난다. AOP로 숨기지 않은 이유는
        // 어노테이션 하나로는 "왜 403인지" 추적이 안 되고, Task 020이 지운 @DistributedLock AOP를
        // 되살리는 셈이 되기 때문이다. 호출 흐름에 한 줄로 보이는 쪽이 낫다.
        //
        // attempts 카운터 **밖에서** 부른다. 대기열 거절은 "예매 시도의 실패"가 아니라 시도에
        // 도달하지 못한 것이다. 안에서 세면 예매 성공률이 대기열 거절로 오염되고, 거절 자체는
        // tikkit.queue.pass.checks가 이미 세고 있어 중복이 된다.
        //
        // 대기열이 꺼져 있거나 이 회차의 플래그가 false면 DisabledQueueGateway/플래그 검사가
        // 즉시 통과시킨다 — Redis를 건드리지 않는다.
        queueGateway.requireAdmitted(request.scheduleId(), memberId);

        try {
            ReservationResponse response = createPending(memberId, request);
            attemptCounter(RESULT_SUCCESS, REASON_NONE).increment();
            return response;
        } catch (BusinessException e) {
            attemptCounter(RESULT_FAILURE, e.getErrorCode().name()).increment();
            throw e;
        } catch (RuntimeException e) {
            // 조건부 UPDATE 불변식 위반(IllegalStateException)이나 되던진 DataIntegrityViolation.
            // 안 세면 failure 합계가 실제 실패보다 작아져 실패율이 거짓이 된다.
            attemptCounter(RESULT_FAILURE, REASON_UNEXPECTED).increment();
            throw e;
        }
    }

    /**
     * 예매 선점(PENDING 홀드)을 생성한다.
     * <p>
     * 좌석 선점은 조건부 UPDATE({@code ScheduleSeatRepository.holdSeats})가 담당한다. 읽은 값을
     * 애플리케이션이 다시 쓰지 않으므로 lost update가 구조적으로 불가능하다
     * (비관적·낙관적 락과의 비교: {@code docs/improvements/002-db-lock-comparison.md}).
     * <p>
     * 지정석 전환으로 경쟁 대상이 {@code ticket_grades} 한 행에서 {@code schedule_seats} N행으로
     * 바뀌었다. 한 행을 다툴 때와 달리 "요청한 4석 중 2석만 성공"이 가능하므로, 바뀐 행 수가 요청
     * 좌석 수와 같은지 검사해 다르면 예외로 롤백시킨다 — 부분 선점을 DB 제약이 아니라 이 검사가 막는다.
     * <p>
     * <b>예약을 좌석보다 먼저 INSERT한다.</b> {@code ck_schedule_seats_status_holder}가 HELD 좌석에
     * {@code reservation_id}를 요구하고 그 컬럼이 {@code reservations} FK라, 예약 행이 없으면 좌석을
     * 잡을 수가 없다. 전환 전에는 재고 차감이 먼저였는데 순서가 뒤집힌 지점이다.
     */
    private ReservationResponse createPending(Long memberId, ReservationCreateRequest request) {
        // 회차를 fetch join으로 함께 받는다. findById로 등급만 읽으면 바로 아래 isBookingOpen에서
        // Schedule 프록시가 초기화되면서 SELECT가 한 번 더 나간다 (Task 028, 왕복 7 -> 6).
        TicketGrade ticketGrade = ticketGradeRepository.findByIdWithSchedule(request.ticketGradeId())
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

        List<Long> seatIds = normalizeSeatIds(request);

        // 같은 회원이 같은 등급에 이미 선점 중인 PENDING 예약이 있으면 중복 선점을 막는다.
        if (reservationRepository.existsByMemberIdAndTicketGradeIdAndStatus(
                memberId, ticketGrade.getId(), ReservationStatus.PENDING)) {
            throw new BusinessException(ErrorCode.DUPLICATE_PENDING_RESERVATION);
        }

        // 고른 좌석이 모두 이 회차·이 등급에 속하는지 확인한다. 세 테이블에 걸친 조건이라 DB CHECK로는
        // 표현할 수 없다 (docs/ROADMAP.md Task 022). 아래 선점 UPDATE가 같은 조건을 다시 걸므로
        // 이건 최종 방어선이 아니다 — "없는 좌석"(404)과 "이미 팔린 좌석"(409)을 구분해 주기 위한 조회다.
        if (scheduleSeatRepository.countMatching(seatIds, schedule.getId(), ticketGrade.getId()) != seatIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
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

        // 엔티티 필드를 건드리지 않는 것이 중요하다 — 영속 인스턴스가 더티가 되면 flush 시점에
        // Hibernate가 메모리의 낡은 값으로 UPDATE를 또 발행해 이 조건부 UPDATE를 덮어쓴다.
        int held = scheduleSeatRepository.holdSeats(
                seatIds, reservation.getId(), ticketGrade.getId(), reservation.getUnitPrice(), now);
        if (held != seatIds.size()) {
            // 한 석이라도 놓쳤으면 전부 되돌린다. 좌석을 직접 고르는 UX에서는 "고른 자리 중 누가 먼저
            // 잡았다"가 전부라, 몇 석이 남았는지는 의미가 없으므로 기존 SOLD_OUT을 그대로 쓴다.
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }

        return toResponse(reservation);
    }

    /**
     * 좌석 ID를 중복 제거하고 정렬한다.
     * <p>
     * <b>정렬이 핵심이다.</b> 여러 사용자가 겹치는 좌석 집합을 동시에 선점할 때, 락을 잡는 순서가
     * 요청마다 다르면 서로를 기다리는 데드락이 생긴다. 모든 요청이 같은 순서로 잡게 만들면
     * 그 순환이 애초에 성립하지 않는다.
     * <p>
     * 중복과 매수 불일치는 {@code @Valid}로 걸러낼 수 없는 교차 필드 조건이라 여기서 400으로 끊는다.
     * 중복을 조용히 제거하지 않는 이유: 그러면 선점된 좌석 수가 매수보다 적어져 아래 검사에서
     * SOLD_OUT(409)으로 떨어지는데, 실제 원인은 잘못된 요청이라 에러가 사용자를 오해하게 만든다.
     */
    private List<Long> normalizeSeatIds(ReservationCreateRequest request) {
        List<Long> seatIds = request.seatIds().stream().distinct().sorted().toList();
        if (seatIds.size() != request.seatIds().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "같은 좌석을 중복해서 선택했습니다.");
        }
        if (seatIds.size() != request.quantity()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "선택한 좌석 수(%d)와 매수(%d)가 다릅니다.".formatted(seatIds.size(), request.quantity()));
        }
        return seatIds;
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

        // 좌석은 1:N이라 예약 본문과 한 쿼리로 합치지 않는다 (ReservationRepositoryCustom.findSeats Javadoc 참조).
        List<ReservationSeatResponse> seats = reservationRepository.findSeats(reservation.getId());

        return new ReservationDetailResponse(
                reservation.getId(), reservation.getReservationNo(),
                reservation.getSchedule().getPerformance().getTitle(), reservation.getSchedule().getShowAt(),
                reservation.getTicketGrade().getGrade(), reservation.getQuantity(), reservation.getUnitPrice(),
                reservation.getTotalAmount(), reservation.getStatus(), reservation.getExpiresAt(),
                reservation.getConfirmedAt(), reservation.getCancelledAt(), seats, payment);
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

        // 선점 좌석을 판매 완료로 전이한다. 위 확정 전이를 통과했다는 건 만료 배치도 취소도 이 예약을
        // 건드리지 못했다는 뜻이라 좌석은 HELD여야 한다. 건수가 어긋나면 불변식이 깨진 것이므로,
        // 승인된 결제를 되돌리고 롤백시킨다 — 조용히 넘기면 돈은 받고 좌석은 안 넘긴 상태가 된다.
        int sold = scheduleSeatRepository.markSold(id, now);
        if (sold != reservation.getQuantity()) {
            compensateApprovedPayment(transactionKey);
            throw new IllegalStateException(
                    "예약(id=%d) 확정 중 선점 좌석 %d석 가운데 %d석만 전이됐습니다."
                            .formatted(id, reservation.getQuantity(), sold));
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
     * CONFIRMED 취소는 결제를 환불 처리하고, 두 상태 모두 취소 시 점유한 좌석을 반환한다.
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

        // 좌석을 예매 가능 상태로 되돌린다. PENDING이면 HELD, CONFIRMED면 SOLD 좌석을 반납하는데
        // 둘 다 "현재 점유자가 이 예약"이라는 같은 조건으로 잡히므로 분기가 필요 없다.
        // 위 조건부 전이를 통과한 트랜잭션만 여기 오므로 이중 반납이 일어날 수 없다.
        int released = scheduleSeatRepository.releaseSeats(id, now);
        if (released != reservation.getQuantity()) {
            // 조용히 넘기면 좌석이 영구히 팔리지 않는 상태로 남으므로 롤백시켜 전이까지 되돌린다.
            throw new IllegalStateException(
                    "예약(id=%d) 취소 중 좌석 %d석 가운데 %d석만 반환됐습니다."
                            .formatted(id, reservation.getQuantity(), released));
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
