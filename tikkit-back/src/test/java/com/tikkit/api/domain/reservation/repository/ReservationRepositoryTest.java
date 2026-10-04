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
import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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

    @Test
    @DisplayName("만료 배치는 같은 등급에서 여러 건이 동시에 만료돼도 수량을 합산해 잔여 수량을 복원한다")
    void 만료_배치_같은등급_여러건_합산_복원() {
        // given: 같은 등급에 서로 다른 회원의 PENDING 2건이 이미 만료돼 있다
        Instant now = Instant.now();
        Instant pastExpiresAt = now.minus(1, ChronoUnit.MINUTES);
        Member other = memberRepository.save(Member.builder()
                .email("other@tikkit.com").password("encoded").name("김철수").phone("010-2222-3333")
                .role(MemberRole.USER).build());
        Reservation expired1 = reservationRepository.save(pendingReservation(member, 2, pastExpiresAt));
        Reservation expired2 = reservationRepository.save(pendingReservation(other, 3, pastExpiresAt));
        // 실제 선점 흐름(ReservationService.create)처럼 선점 시점에 잔여 수량을 미리 차감해둔다 —
        // 그래야 배치가 복원했을 때 remaining_quantity <= total_quantity CHECK 제약을 어기지 않는다.
        // Task 019부터 재고 차감은 조건부 UPDATE가 담당한다.
        ticketGradeRepository.decreaseRemainingQuantity(ticketGrade.getId(), 2 + 3, now);
        em.flush();
        em.clear();

        // when
        int affected = reservationRepository.expirePendingReservations(now);

        // then
        assertThat(affected).isEqualTo(1); // ticket_grades 기준 반영 건수(등급 1개가 합산 반영됨)
        assertThat(reservationRepository.findById(expired1.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservationRepository.findById(expired2.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        // 선점 시 5(=2+3) 차감했다가 배치로 그대로 복원되므로 원래 수량(10)으로 돌아온다
        assertThat(ticketGradeRepository.findById(ticketGrade.getId()).orElseThrow().getRemainingQuantity())
                .isEqualTo(10);
    }

    @Test
    @DisplayName("만료 배치는 아직 만료 시각이 안 지난 PENDING과 CONFIRMED 예약은 건드리지 않는다")
    void 만료_배치_대상이_아닌_예약은_건드리지_않음() {
        // given
        Instant now = Instant.now();
        Reservation stillPending = reservationRepository.save(
                pendingReservation(member, 1, now.plus(5, ChronoUnit.MINUTES)));
        Reservation confirmed = reservationRepository.save(Reservation.builder()
                .reservationNo("TK" + UUID.randomUUID().toString().substring(0, 8))
                .member(member).schedule(schedule).ticketGrade(ticketGrade)
                .quantity(1).unitPrice(ticketGrade.getPrice()).totalAmount(ticketGrade.getPrice())
                .status(ReservationStatus.CONFIRMED)
                .expiresAt(now.minus(1, ChronoUnit.MINUTES)) // 확정 후에도 남아있는 과거 expiresAt — 상태가 CONFIRMED라 대상이 아님
                .confirmedAt(now.minus(10, ChronoUnit.MINUTES))
                .build());
        em.flush();
        em.clear();

        // when
        reservationRepository.expirePendingReservations(now);

        // then
        assertThat(reservationRepository.findById(stillPending.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING);
        assertThat(reservationRepository.findById(confirmed.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(ticketGradeRepository.findById(ticketGrade.getId()).orElseThrow().getRemainingQuantity())
                .isEqualTo(10); // 복원 없음
    }

    @Test
    @DisplayName("본인 예약만 상태로 필터링해 최신순으로 페이징 조회한다")
    void 내예약_상태필터_페이징() {
        // given
        Member other = memberRepository.save(Member.builder()
                .email("other2@tikkit.com").password("encoded").name("김철수").phone("010-2222-3333")
                .role(MemberRole.USER).build());
        reservationRepository.save(pendingReservation(member, 1, Instant.now().plus(10, ChronoUnit.MINUTES)));
        reservationRepository.save(Reservation.builder()
                .reservationNo("TK" + UUID.randomUUID().toString().substring(0, 8))
                .member(member).schedule(schedule).ticketGrade(ticketGrade)
                .quantity(1).unitPrice(ticketGrade.getPrice()).totalAmount(ticketGrade.getPrice())
                .status(ReservationStatus.CANCELLED).cancelledAt(Instant.now())
                .build());
        reservationRepository.save(pendingReservation(other, 1, Instant.now().plus(10, ChronoUnit.MINUTES)));

        // when
        Page<ReservationSummaryResponse> result = reservationRepository.searchMine(
                member.getId(), ReservationStatus.PENDING, PageRequest.of(0, 20));

        // then: 다른 회원 예약, 상태가 다른 예약은 빠지고 본인의 PENDING 1건만 조회된다
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(ReservationSummaryResponse::status)
                .containsOnly(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("다른 회원 소유의 예약을 조회하면 빈 Optional을 반환한다")
    void 상세조회_다른회원_소유_예약() {
        // given
        Member other = memberRepository.save(Member.builder()
                .email("other3@tikkit.com").password("encoded").name("김철수").phone("010-2222-3333")
                .role(MemberRole.USER).build());
        Reservation mine = reservationRepository.save(pendingReservation(member, 1, Instant.now().plus(10, ChronoUnit.MINUTES)));

        // when
        Optional<Reservation> asOwner = reservationRepository.findMineWithDetails(mine.getId(), member.getId());
        Optional<Reservation> asOther = reservationRepository.findMineWithDetails(mine.getId(), other.getId());

        // then
        assertThat(asOwner).isPresent();
        assertThat(asOther).isEmpty();
    }

    private Reservation pendingReservation(Member owner, int quantity, Instant expiresAt) {
        return Reservation.builder()
                .reservationNo("TK" + UUID.randomUUID().toString().substring(0, 8))
                .member(owner)
                .schedule(schedule)
                .ticketGrade(ticketGrade)
                .quantity(quantity)
                .unitPrice(ticketGrade.getPrice())
                .totalAmount(ticketGrade.getPrice().multiply(BigDecimal.valueOf(quantity)))
                .status(ReservationStatus.PENDING)
                .expiresAt(expiresAt)
                .build();
    }
}
