package com.tikkit.api.domain.reservation.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.payment.entity.Payment;
import com.tikkit.api.domain.payment.entity.PaymentMethod;
import com.tikkit.api.domain.payment.entity.PaymentStatus;
import com.tikkit.api.domain.payment.gateway.PaymentGateway;
import com.tikkit.api.domain.payment.repository.PaymentRepository;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.reservation.dto.PaymentRequest;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import com.tikkit.api.domain.reservation.strategy.NoLockStrategy;
import com.tikkit.api.domain.reservation.strategy.RetryMetrics;
import com.tikkit.api.domain.reservation.strategy.SeatHoldStrategyHolder;
import com.tikkit.api.domain.venue.entity.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long SCHEDULE_ID = 10L;
    private static final Long TICKET_GRADE_ID = 100L;
    private static final Long RESERVATION_ID = 200L;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private TicketGradeRepository ticketGradeRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ReservationNoGenerator reservationNoGenerator;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private SeatHoldStrategyHolder seatHoldStrategyHolder;

    @Mock
    private RetryMetrics retryMetrics;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private ReservationService reservationService;

    /**
     * create()가 트랜잭션 템플릿과 전략을 거치도록 최소한만 스텁한다.
     * <p>
     * TransactionTemplate을 목으로 두면 execute()가 콜백을 실행하지 않고 null을 반환해서 create()의
     * 결과가 사라진다. 트랜잭션 경계 자체는 이 유닛 테스트의 관심사가 아니므로(통합 테스트가 다룬다)
     * 콜백을 그대로 실행시킨다. 결제·취소 테스트는 이 둘을 쓰지 않으므로 lenient로 둔다.
     */
    @BeforeEach
    void stubHoldPath() {
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0)
                        .doInTransaction(null));
        lenient().when(seatHoldStrategyHolder.current()).thenReturn(new NoLockStrategy());
    }

    /** 공연 시작(showAt) 시각을 자유롭게 지정할 수 있는 등급을 만든다. 결제/취소 테스트에서 쓴다. */
    private TicketGrade gradeWithShowAt(Instant showAt, int remainingQuantity) {
        Venue venue = Venue.builder().name("테스트 공연장").address("서울").build();
        Performance performance = Performance.builder()
                .title("테스트 공연")
                .category(PerformanceCategory.CONCERT)
                .venue(venue)
                .runningMinutes(120)
                .ageRating("전체 관람가")
                .status(PerformanceStatus.ON_SALE)
                .build();
        Schedule schedule = Schedule.builder()
                .performance(performance)
                .showAt(showAt)
                .bookingOpenAt(showAt.minus(30, ChronoUnit.DAYS))
                .bookingCloseAt(showAt.minus(1, ChronoUnit.HOURS))
                .build();
        return TicketGrade.builder()
                .schedule(schedule)
                .grade(Grade.VIP)
                .price(new BigDecimal("150000"))
                .totalQuantity(10)
                .remainingQuantity(remainingQuantity)
                .build();
    }

    /** 결제/취소 테스트용 예약. quantity 2, 단가*수량으로 totalAmount를 맞춘다. */
    private Reservation reservation(ReservationStatus status, Instant expiresAt, TicketGrade grade) {
        Reservation r = Reservation.builder()
                .reservationNo("TK260927-000001")
                .schedule(grade.getSchedule())
                .ticketGrade(grade)
                .quantity(2)
                .unitPrice(grade.getPrice())
                .totalAmount(grade.getPrice().multiply(BigDecimal.valueOf(2)))
                .status(status)
                .expiresAt(expiresAt)
                .build();
        ReflectionTestUtils.setField(r, "id", RESERVATION_ID);
        return r;
    }

    private TicketGrade ticketGrade(Instant bookingOpenAt, Instant bookingCloseAt, int remainingQuantity) {
        Venue venue = Venue.builder().name("테스트 공연장").address("서울").build();
        Performance performance = Performance.builder()
                .title("테스트 공연")
                .category(PerformanceCategory.CONCERT)
                .venue(venue)
                .runningMinutes(120)
                .ageRating("전체 관람가")
                .status(PerformanceStatus.ON_SALE)
                .build();
        Schedule schedule = Schedule.builder()
                .performance(performance)
                .showAt(bookingCloseAt.plus(1, ChronoUnit.HOURS))
                .bookingOpenAt(bookingOpenAt)
                .bookingCloseAt(bookingCloseAt)
                .build();
        ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);

        TicketGrade grade = TicketGrade.builder()
                .schedule(schedule)
                .grade(Grade.VIP)
                .price(new BigDecimal("150000"))
                .totalQuantity(10)
                .remainingQuantity(remainingQuantity)
                .build();
        ReflectionTestUtils.setField(grade, "id", TICKET_GRADE_ID);
        return grade;
    }

    @Test
    @DisplayName("예매 가능 기간이고 재고가 충분하면 재고를 차감하고 PENDING 예약을 생성한다")
    void 선점_성공() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 2);
        Member member = Member.builder()
                .email("test@tikkit.com").password("encoded").name("홍길동").phone("010-1111-2222")
                .role(MemberRole.USER).build();

        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));
        given(memberRepository.getReferenceById(MEMBER_ID)).willReturn(member);
        given(reservationNoGenerator.generate(any())).willReturn("TK260927-000001");

        // when
        ReservationResponse response = reservationService.create(MEMBER_ID, request);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(response.reservationNo()).isEqualTo("TK260927-000001");
        assertThat(response.expiresAt()).isNotNull();
        assertThat(grade.getRemainingQuantity()).isEqualTo(3);
        verify(reservationRepository).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 등급이면 NOT_FOUND 예외를 던지고 저장하지 않는다")
    void 등급_없음() {
        // given
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.empty());
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 1);

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("요청한 scheduleId가 등급이 속한 회차와 다르면 NOT_FOUND 예외를 던진다")
    void 회차_불일치() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(999L, TICKET_GRADE_ID, 1);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("예매 시작 전이면 BOOKING_NOT_OPEN 예외를 던진다")
    void 오픈_전() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.plus(1, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 1);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BOOKING_NOT_OPEN);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("예매 마감 후면 BOOKING_NOT_OPEN 예외를 던진다")
    void 마감_후() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 1);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BOOKING_NOT_OPEN);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 회원이 같은 등급에 이미 PENDING 예약이 있으면 DUPLICATE_PENDING_RESERVATION 예외를 던진다")
    void 중복_선점() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 1);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));
        given(reservationRepository.existsByMemberIdAndTicketGradeIdAndStatus(
                MEMBER_ID, TICKET_GRADE_ID, ReservationStatus.PENDING)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_PENDING_RESERVATION);
        verify(reservationRepository, never()).save(any());
        // 중복 체크에서 걸렸으므로 재고는 차감되지 않아야 한다
        assertThat(grade.getRemainingQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("잔여 수량보다 많이 요청하면 SOLD_OUT 예외를 던진다")
    void 재고_부족() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 1);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 2);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SOLD_OUT);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("PENDING이고 아직 만료 전이면 결제를 승인하고 CONFIRMED로 확정한다")
    void 결제_성공() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = gradeWithShowAt(now.plus(30, ChronoUnit.DAYS), 5);
        Reservation reservation = reservation(ReservationStatus.PENDING, now.plus(5, ChronoUnit.MINUTES), grade);
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.of(reservation));
        given(paymentGateway.approve(reservation.getReservationNo(), reservation.getTotalAmount(), PaymentMethod.CARD))
                .willReturn("mock-tx-key");

        // when
        ReservationResponse response = reservationService.pay(MEMBER_ID, RESERVATION_ID, new PaymentRequest(PaymentMethod.CARD));

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @DisplayName("만료 시각이 지난 PENDING 예약은 배치가 아직 안 돌았어도 결제 시 RESERVATION_EXPIRED를 던진다")
    void 결제_실패_만료시각_경과() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = gradeWithShowAt(now.plus(30, ChronoUnit.DAYS), 5);
        Reservation reservation = reservation(ReservationStatus.PENDING, now.minus(1, ChronoUnit.MINUTES), grade);
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.pay(MEMBER_ID, RESERVATION_ID, new PaymentRequest(PaymentMethod.CARD)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_EXPIRED);
        verify(paymentGateway, never()).approve(anyString(), any(), any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("배치로 이미 EXPIRED 처리된 예약은 결제 시 RESERVATION_EXPIRED를 던진다")
    void 결제_실패_이미_EXPIRED() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = gradeWithShowAt(now.plus(30, ChronoUnit.DAYS), 5);
        Reservation reservation = reservation(ReservationStatus.EXPIRED, now.minus(1, ChronoUnit.HOURS), grade);
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.pay(MEMBER_ID, RESERVATION_ID, new PaymentRequest(PaymentMethod.CARD)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_EXPIRED);
    }

    @Test
    @DisplayName("이미 CONFIRMED인 예약을 다시 결제하려 하면 INVALID_STATUS_TRANSITION을 던진다")
    void 결제_실패_이미_확정() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = gradeWithShowAt(now.plus(30, ChronoUnit.DAYS), 5);
        Reservation reservation = reservation(ReservationStatus.CONFIRMED, now.plus(5, ChronoUnit.MINUTES), grade);
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.pay(MEMBER_ID, RESERVATION_ID, new PaymentRequest(PaymentMethod.CARD)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("다른 회원 소유거나 존재하지 않는 예약을 결제하려 하면 NOT_FOUND를 던진다")
    void 결제_실패_소유자_아님() {
        // given
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.pay(MEMBER_ID, RESERVATION_ID, new PaymentRequest(PaymentMethod.CARD)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("PENDING 예약은 시점 제한 없이 취소되고 잔여 수량이 복원된다")
    void 취소_PENDING_재고복원() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = gradeWithShowAt(now.plus(30, ChronoUnit.DAYS), 3);
        Reservation reservation = reservation(ReservationStatus.PENDING, now.minus(1, ChronoUnit.MINUTES), grade); // 만료 시각 지나도 취소는 허용
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.of(reservation));

        // when
        ReservationResponse response = reservationService.cancel(MEMBER_ID, RESERVATION_ID);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(grade.getRemainingQuantity()).isEqualTo(5); // 3 + quantity(2)
        verify(paymentGateway, never()).refund(anyString());
    }

    @Test
    @DisplayName("CONFIRMED 예약은 취소 마감 전이면 환불 처리 후 취소되고 잔여 수량이 복원된다")
    void 취소_CONFIRMED_마감전_환불() {
        // given: 공연 시작이 충분히 남아있어 24시간 취소 마감 전이다
        Instant now = Instant.now();
        TicketGrade grade = gradeWithShowAt(now.plus(30, ChronoUnit.DAYS), 3);
        Reservation reservation = reservation(ReservationStatus.CONFIRMED, now.plus(5, ChronoUnit.MINUTES), grade);
        Payment payment = Payment.paid(reservation, PaymentMethod.CARD, "mock-tx-key", now.minus(1, ChronoUnit.HOURS));
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.of(reservation));
        given(paymentRepository.findByReservationId(RESERVATION_ID)).willReturn(Optional.of(payment));

        // when
        ReservationResponse response = reservationService.cancel(MEMBER_ID, RESERVATION_ID);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(grade.getRemainingQuantity()).isEqualTo(5);
        verify(paymentGateway).refund("mock-tx-key");
    }

    @Test
    @DisplayName("CONFIRMED 예약은 공연 24시간 전 이후 취소하려 하면 CANCEL_DEADLINE_PASSED를 던진다")
    void 취소_CONFIRMED_마감후() {
        // given: 공연 시작까지 24시간이 채 안 남았다
        Instant now = Instant.now();
        TicketGrade grade = gradeWithShowAt(now.plus(12, ChronoUnit.HOURS), 3);
        Reservation reservation = reservation(ReservationStatus.CONFIRMED, now.plus(5, ChronoUnit.MINUTES), grade);
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.cancel(MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CANCEL_DEADLINE_PASSED);
        verify(paymentGateway, never()).refund(anyString());
        assertThat(grade.getRemainingQuantity()).isEqualTo(3); // 복원되지 않음
    }

    @Test
    @DisplayName("이미 CANCELLED이거나 EXPIRED인 예약을 다시 취소하려 하면 INVALID_STATUS_TRANSITION을 던진다")
    void 취소_실패_이미_종결된_예약() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = gradeWithShowAt(now.plus(30, ChronoUnit.DAYS), 3);
        Reservation cancelled = reservation(ReservationStatus.CANCELLED, now.minus(1, ChronoUnit.HOURS), grade);
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.of(cancelled));

        // when & then
        assertThatThrownBy(() -> reservationService.cancel(MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("다른 회원 소유거나 존재하지 않는 예약을 취소하려 하면 NOT_FOUND를 던진다")
    void 취소_실패_소유자_아님() {
        // given
        given(reservationRepository.findMineWithDetails(RESERVATION_ID, MEMBER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.cancel(MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }
}
