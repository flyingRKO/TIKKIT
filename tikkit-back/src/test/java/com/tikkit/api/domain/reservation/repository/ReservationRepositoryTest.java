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
import com.tikkit.api.domain.performance.entity.ScheduleSeat;
import com.tikkit.api.domain.performance.entity.SeatStatus;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import com.tikkit.api.domain.venue.entity.Seat;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.SeatRepository;
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
import java.util.ArrayList;
import java.util.List;
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
    private SeatRepository seatRepository;
    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;
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
    @DisplayName("만료 배치는 만료된 예약이 점유한 좌석을 예매 가능 상태로 반환한다")
    void 만료_배치_좌석_반환() {
        // given: 같은 등급에 서로 다른 회원의 PENDING 2건이 이미 만료돼 있고, 각자 좌석을 점유 중이다
        // (V5 백필이 과거 PENDING을 HELD로 만들어 놨는데 이 배치가 좌석을 몰라 고착됐던 문제 — Task 021 알려진 한계)
        Instant now = Instant.now();
        Instant pastExpiresAt = now.minus(1, ChronoUnit.MINUTES);
        Member other = memberRepository.save(Member.builder()
                .email("other@tikkit.com").password("encoded").name("김철수").phone("010-2222-3333")
                .role(MemberRole.USER).build());
        Reservation expired1 = reservationRepository.save(pendingReservation(member, 2, pastExpiresAt));
        Reservation expired2 = reservationRepository.save(pendingReservation(other, 3, pastExpiresAt));

        List<Long> seatIds = createAvailableSeats(5);
        holdSeats(expired1, seatIds.subList(0, 2), now);
        holdSeats(expired2, seatIds.subList(2, 5), now);
        assertThat(availableSeatCount()).as("선점으로 예매 가능 좌석이 모두 소진됐다").isZero();

        // when
        int releasedSeats = reservationRepository.expirePendingReservations(now);

        // then: 반환값이 등급 수가 아니라 좌석 수다 (한 예약이 여러 좌석을 가진다)
        assertThat(releasedSeats).isEqualTo(5);
        assertThat(reservationRepository.findById(expired1.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservationRepository.findById(expired2.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(availableSeatCount()).as("점유 좌석 5석이 모두 반환됐다").isEqualTo(5);
    }

    @Test
    @DisplayName("만료 배치는 아직 만료 시각이 안 지난 PENDING과 CONFIRMED 예약의 좌석은 건드리지 않는다")
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

        // 좌석 3석 중 1석은 선점, 1석은 판매 완료, 1석은 그대로 남겨둔다
        List<Long> seatIds = createAvailableSeats(3);
        holdSeats(stillPending, seatIds.subList(0, 1), now);
        holdSeats(confirmed, seatIds.subList(1, 2), now);
        assertThat(scheduleSeatRepository.markSold(confirmed.getId(), now)).isEqualTo(1);

        // when
        int releasedSeats = reservationRepository.expirePendingReservations(now);

        // then
        assertThat(releasedSeats).as("반환된 좌석이 없다").isZero();
        assertThat(reservationRepository.findById(stillPending.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING);
        assertThat(reservationRepository.findById(confirmed.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(availableSeatCount()).as("남겨둔 1석만 예매 가능하다").isEqualTo(1);
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


    @Test
    @DisplayName("같은 회원이 같은 등급에 PENDING 예약을 둘 만들면 부분 유니크 인덱스를 위반한다")
    void 중복_선점_부분_유니크_인덱스() {
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        reservationRepository.saveAndFlush(pendingReservation(member, 1, expiresAt));

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(pendingReservation(member, 1, expiresAt)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    // 서비스가 이 이름으로 다른 제약 위반과 구분해 409로 바꾼다 (ReservationService.create).
                    // 제약명이 실제로 어떻게 담기는지는 DB·드라이버·Hibernate에 달려 있어서 여기서 못 박아 둔다.
                    String name = constraintNameOf(e);
                    assertThat(name).as("제약명을 꺼낼 수 있다. 실제 값: %s", name)
                            .isEqualTo("uk_reservations_pending_member_grade");
                });
    }

    @Test
    @DisplayName("부분 유니크 인덱스는 PENDING만 제한하므로 앞 예약이 CANCELLED면 다시 선점할 수 있다")
    void 중복_선점_부분_조건() {
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        Reservation first = reservationRepository.saveAndFlush(pendingReservation(member, 1, expiresAt));
        reservationRepository.cancelIfStatus(first.getId(), ReservationStatus.PENDING, Instant.now());
        em.clear();

        Reservation second = reservationRepository.saveAndFlush(pendingReservation(member, 1, expiresAt));

        assertThat(second.getId()).as("CANCELLED는 인덱스 조건 밖이라 새 PENDING을 만들 수 있다").isNotNull();
    }

    /** DataIntegrityViolationException 원인 체인에서 Hibernate가 채운 제약명을 꺼낸다. */
    private String constraintNameOf(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
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

    /** 회차에 예매 가능 좌석을 count개 만들고 schedule_seats id 목록을 돌려준다. */
    private List<Long> createAvailableSeats(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int seatNumber = 1; seatNumber <= count; seatNumber++) {
            Seat seat = seatRepository.save(Seat.builder()
                    .venue(schedule.getPerformance().getVenue())
                    .section("VIP-중").rowLabel("1").seatNumber(seatNumber)
                    .posX(seatNumber).posY(1).build());
            ids.add(scheduleSeatRepository.save(ScheduleSeat.builder()
                    .schedule(schedule).seat(seat).ticketGrade(ticketGrade)
                    .status(SeatStatus.AVAILABLE).build()).getId());
        }
        return ids;
    }

    /**
     * 운영 선점 쿼리를 그대로 써서 픽스처를 만든다. 테스트 전용 INSERT를 따로 두면 실제 선점 경로와
     * 조용히 어긋날 수 있고, ck_schedule_seats_status_holder 같은 제약도 우회하게 된다.
     */
    private void holdSeats(Reservation reservation, List<Long> seatIds, Instant now) {
        int held = scheduleSeatRepository.holdSeats(
                seatIds, reservation.getId(), ticketGrade.getId(), reservation.getUnitPrice(), now);
        assertThat(held).as("픽스처 선점이 성공했다").isEqualTo(seatIds.size());
    }

    /** 벌크 UPDATE는 1차 캐시를 갱신하지 않으므로 스칼라 집계로 DB 값을 직접 읽는다. */
    private int availableSeatCount() {
        return scheduleSeatRepository.countAvailableByScheduleId(schedule.getId())
                .getOrDefault(ticketGrade.getId(), 0);
    }
}
