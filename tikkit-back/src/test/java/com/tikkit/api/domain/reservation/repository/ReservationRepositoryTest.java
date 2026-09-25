package com.tikkit.api.domain.reservation.repository;

import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.payment.entity.Payment;
import com.tikkit.api.domain.payment.entity.PaymentMethod;
import com.tikkit.api.domain.payment.entity.PaymentStatus;
import com.tikkit.api.domain.payment.repository.PaymentRepository;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.VenueRepository;
import com.tikkit.api.support.AbstractContainerTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class ReservationRepositoryTest extends AbstractContainerTest {

    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private TicketGradeRepository ticketGradeRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private PaymentRepository paymentRepository;

    @PersistenceContext
    private EntityManager em;

    private Member member;
    private Schedule schedule;
    private TicketGrade ticketGrade;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder().name("테스트 공연장").address("서울").build());
        Performance performance = performanceRepository.save(Performance.builder()
                .title("테스트 공연")
                .category(PerformanceCategory.CONCERT)
                .venue(venue)
                .runningMinutes(120)
                .ageRating("전체 관람가")
                .status(PerformanceStatus.ON_SALE)
                .build());
        schedule = scheduleRepository.save(Schedule.builder()
                .performance(performance)
                .showAt(Instant.now().plus(10, ChronoUnit.DAYS))
                .bookingOpenAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .bookingCloseAt(Instant.now().plus(9, ChronoUnit.DAYS))
                .build());
        ticketGrade = ticketGradeRepository.save(TicketGrade.builder()
                .schedule(schedule)
                .grade(Grade.VIP)
                .price(new BigDecimal("150000"))
                .totalQuantity(10)
                .remainingQuantity(10)
                .build());
        member = memberRepository.save(Member.builder()
                .email("test@tikkit.com")
                .password("encoded")
                .name("홍길동")
                .phone("010-1111-2222")
                .role(MemberRole.USER)
                .build());
    }

    @Test
    @DisplayName("예약~결제 전체 그래프를 저장하고 재조회하면 enum·금액·시각 값이 그대로 유지된다")
    void 예약_결제_그래프_저장_후_재조회() {
        // given
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .reservationNo("TK" + UUID.randomUUID().toString().substring(0, 8))
                .member(member)
                .schedule(schedule)
                .ticketGrade(ticketGrade)
                .quantity(2)
                .unitPrice(new BigDecimal("150000"))
                .totalAmount(new BigDecimal("300000"))
                .status(ReservationStatus.CONFIRMED)
                .confirmedAt(Instant.now())
                .build());
        paymentRepository.save(Payment.builder()
                .reservation(reservation)
                .amount(new BigDecimal("300000"))
                .method(PaymentMethod.CARD)
                .status(PaymentStatus.PAID)
                .transactionKey(UUID.randomUUID().toString())
                .paidAt(Instant.now())
                .build());

        // when
        em.flush();
        em.clear();
        Reservation reloaded = reservationRepository.findById(reservation.getId()).orElseThrow();

        // then
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("300000");
        assertThat(reloaded.getConfirmedAt()).isNotNull();
        assertThat(reloaded.getTicketGrade().getGrade()).isEqualTo(Grade.VIP);
    }

    @Test
    @DisplayName("총 결제 금액이 단가*수량과 다르면 CHECK 제약을 위반해 저장에 실패한다")
    void 총액_불일치시_CHECK_제약_위반() {
        Reservation invalid = Reservation.builder()
                .reservationNo("TK" + UUID.randomUUID().toString().substring(0, 8))
                .member(member)
                .schedule(schedule)
                .ticketGrade(ticketGrade)
                .quantity(2)
                .unitPrice(new BigDecimal("150000"))
                .totalAmount(new BigDecimal("999999"))
                .status(ReservationStatus.PENDING)
                .build();

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("예약의 schedule과 ticketGrade가 서로 다른 회차를 가리키면 복합 FK 제약을 위반한다")
    void 복합_FK_가드() {
        // given: 회차 B(setUp()의 회차 A와는 다른 스케줄)와 그 등급을 별도로 만든다
        Schedule otherSchedule = scheduleRepository.save(Schedule.builder()
                .performance(ticketGrade.getSchedule().getPerformance())
                .showAt(Instant.now().plus(20, ChronoUnit.DAYS))
                .bookingOpenAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .bookingCloseAt(Instant.now().plus(19, ChronoUnit.DAYS))
                .build());
        TicketGrade otherGrade = ticketGradeRepository.save(TicketGrade.builder()
                .schedule(otherSchedule)
                .grade(Grade.R)
                .price(new BigDecimal("99000"))
                .totalQuantity(10)
                .remainingQuantity(10)
                .build());

        // when: schedule은 회차 A(setUp()), ticketGrade는 회차 B의 것으로 어긋나게 예약을 만든다
        Reservation invalid = Reservation.builder()
                .reservationNo("TK" + UUID.randomUUID().toString().substring(0, 8))
                .member(member)
                .schedule(schedule)
                .ticketGrade(otherGrade)
                .quantity(1)
                .unitPrice(otherGrade.getPrice())
                .totalAmount(otherGrade.getPrice())
                .status(ReservationStatus.PENDING)
                .build();

        // then
        assertThatThrownBy(() -> reservationRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
