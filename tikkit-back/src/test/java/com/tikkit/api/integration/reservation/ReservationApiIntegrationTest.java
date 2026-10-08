package com.tikkit.api.integration.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkit.api.domain.member.dto.LoginRequest;
import com.tikkit.api.domain.member.dto.SignupRequest;
import com.tikkit.api.domain.member.entity.Member;
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
import com.tikkit.api.domain.reservation.dto.PaymentRequest;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예매 선점(POST /api/v1/reservations) 흐름을 세션 기반으로 검증한다.
 * MockMvc가 실제 SecurityFilterChain까지 태우므로 별도의 @WebMvcTest는 만들지 않는다.
 */
@AutoConfigureMockMvc
@Transactional
class ReservationApiIntegrationTest extends AbstractContainerTest {

    private static final String PASSWORD = "password1234";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private VenueRepository venueRepository;
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
    private MemberRepository memberRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    /**
     * 판매 중 회차에 미리 깔아두는 좌석 수.
     * <p>
     * 한 테스트 안에서 여러 회원이 각자 다른 좌석을 선점하므로 넉넉하게 둔다.
     * 등급의 수량 컬럼은 더 이상 재고가 아니라, 이 좌석들의 AVAILABLE 건수가 재고다 (Task 022).
     */
    private static final int ON_SALE_SEAT_COUNT = 5;

    private Venue venue;
    private Schedule onSaleSchedule;
    private TicketGrade onSaleGrade;
    private List<Long> onSaleSeatIds;

    /** 좌석을 테스트 안에서 겹치지 않게 나눠 주기 위한 커서. */
    private final AtomicInteger seatCursor = new AtomicInteger();

    @BeforeEach
    void setUp() {
        seatCursor.set(0);
        venue = venueRepository.save(Venue.builder().name("테스트 공연장").address("서울").build());
        Performance performance = performanceRepository.save(Performance.builder()
                .title("테스트 공연")
                .category(PerformanceCategory.CONCERT)
                .venue(venue)
                .runningMinutes(120)
                .ageRating("전체 관람가")
                .status(PerformanceStatus.ON_SALE)
                .build());
        onSaleSchedule = scheduleRepository.save(Schedule.builder()
                .performance(performance)
                .showAt(Instant.now().plus(10, ChronoUnit.DAYS))
                .bookingOpenAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .bookingCloseAt(Instant.now().plus(9, ChronoUnit.DAYS))
                .build());
        onSaleGrade = ticketGradeRepository.save(TicketGrade.builder()
                .schedule(onSaleSchedule)
                .grade(Grade.VIP)
                .price(new BigDecimal("150000"))
                .build());
        onSaleSeatIds = createAvailableSeats(onSaleSchedule, onSaleGrade, "VIP-중", ON_SALE_SEAT_COUNT);
    }

    @Test
    @DisplayName("예매 가능 기간에 로그인 후 좌석을 선점하면 201과 PENDING 예약을 받고, 그 좌석이 예매 가능 목록에서 빠진다")
    void 선점_성공() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("booker1@tikkit.com");
        ReservationCreateRequest request =
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 2, nextSeats(2));

        // when & then
        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reservationNo").value(matchesPattern("^TK\\d{6}-\\d{6}$")))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

        assertThat(availableSeatCount(onSaleGrade.getId()))
                .as("좌석 %d석 중 2석을 선점했다".formatted(ON_SALE_SEAT_COUNT))
                .isEqualTo(ON_SALE_SEAT_COUNT - 2);
    }

    @Test
    @DisplayName("서로 다른 회원이 연속으로 선점하면 예약번호가 서로 다르다")
    void 예약번호_중복없음() throws Exception {
        // given
        MockHttpSession session1 = loginAsNewMember("booker2@tikkit.com");
        MockHttpSession session2 = loginAsNewMember("booker2b@tikkit.com");
        // 두 요청이 같은 좌석을 고르면 두 번째가 SOLD_OUT으로 떨어진다 — 여기서 보려는 건 채번이므로
        // 좌석을 따로 집어 준다.
        ReservationCreateRequest firstRequest =
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 1, nextSeats(1));
        ReservationCreateRequest secondRequest =
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 1, nextSeats(1));

        // when
        MvcResult first = mockMvc.perform(post("/api/v1/reservations")
                        .session(session1)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/reservations")
                        .session(session2)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        // then
        String firstNo = objectMapper.readTree(first.getResponse().getContentAsString()).at("/data/reservationNo").asText();
        String secondNo = objectMapper.readTree(second.getResponse().getContentAsString()).at("/data/reservationNo").asText();
        assertThat(firstNo).isNotEqualTo(secondNo);
    }

    @Test
    @DisplayName("로그인하지 않고 선점을 요청하면 401을 반환한다")
    void 미로그인_선점_실패() throws Exception {
        ReservationCreateRequest request =
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 1, nextSeats(1));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("수량이 4매를 초과하면 400 VALIDATION_ERROR를 반환한다")
    void 수량_초과_요청() throws Exception {
        MockHttpSession session = loginAsNewMember("booker3@tikkit.com");
        ReservationCreateRequest request =
                // 좌석 ID는 아무 값이어도 된다 — DB를 보기 전에 @Size(max = 4)가 먼저 걸린다
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 5,
                        List.of(1L, 2L, 3L, 4L, 5L));

        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("같은 회원이 같은 등급을 연속으로 선점하면 두 번째 요청은 409 DUPLICATE_PENDING_RESERVATION을 반환한다")
    void 중복_선점_실패() throws Exception {
        MockHttpSession session = loginAsNewMember("booker5@tikkit.com");
        ReservationCreateRequest request =
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 1, nextSeats(1));

        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PENDING_RESERVATION"));
    }

    @Test
    @DisplayName("다른 회원이 이미 선점한 좌석을 고르면 409 SOLD_OUT을 반환한다")
    void 선점된_좌석_선점_실패() throws Exception {
        // given: 앞선 회원이 1번 좌석을 잡아둔다
        // 전환 전에는 "잔여 수량보다 많이 요청"이 SOLD_OUT의 조건이었다. 좌석 단위에서는 수량이 아니라
        // "고른 자리를 누가 먼저 잡았는가"가 조건이 된다. 없는 좌석을 고르면 SOLD_OUT이 아니라 404다.
        List<Long> contested = onSaleSeatIds.subList(0, 1);
        reserveViaApi(loginAsNewMember("booker4a@tikkit.com"),
                onSaleSchedule.getId(), onSaleGrade.getId(), contested);
        clearPersistenceContext();

        // when & then: 뒤에 온 회원이 같은 좌석을 고른다
        MockHttpSession session = loginAsNewMember("booker4b@tikkit.com");
        ReservationCreateRequest request = new ReservationCreateRequest(
                onSaleSchedule.getId(), onSaleGrade.getId(), 1, contested);

        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
    }

    @Test
    @DisplayName("선점한 좌석이 DB가 보장하지 못하는 불변식을 모두 지킨다")
    void 좌석_불변식() throws Exception {
        // given: 두 회원이 서로 다른 좌석을 선점한다
        // 아래 네 가지는 테이블 제약으로 표현할 수 없어서 테스트가 지켜야 한다.
        // V5 백필 검증(SeatBackfillMigrationTest)이 보던 불변식인데, V6로 백필 재실행이 불가능해지면서
        // 이제는 선점 경로가 계속 지켜야 하는 조건이 됐다 (Task 022).
        reserveViaApi(loginAsNewMember("invariant1@tikkit.com"),
                onSaleSchedule.getId(), onSaleGrade.getId(), nextSeats(2));
        reserveViaApi(loginAsNewMember("invariant2@tikkit.com"),
                onSaleSchedule.getId(), onSaleGrade.getId(), nextSeats(1));
        clearPersistenceContext();

        // then: 좌석의 공연장이 회차 공연의 공연장과 같다
        // schedule_seats → venue는 3홉이라 복합 FK로 표현할 수 없다
        assertThat(countOf("""
                SELECT count(*) FROM schedule_seats ss
                         JOIN seats st ON st.id = ss.seat_id
                         JOIN schedules sc ON sc.id = ss.schedule_id
                         JOIN performances p ON p.id = sc.performance_id
                WHERE ss.schedule_id = ? AND st.venue_id <> p.venue_id
                """, onSaleSchedule.getId()))
                .as("좌석의 공연장이 회차의 공연장과 어긋난 행").isZero();

        // 한 좌석을 활성 예약 둘이 함께 점유하지 않는다
        // reservation_seats는 append-only라 과거 예약의 행이 남으므로 활성 상태로만 좁혀서 센다
        assertThat(countOf("""
                SELECT count(*) FROM (
                    SELECT rs.schedule_seat_id
                    FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id
                    WHERE r.schedule_id = ? AND r.status IN ('CONFIRMED', 'PENDING')
                    GROUP BY rs.schedule_seat_id HAVING count(*) > 1
                ) duplicated
                """, onSaleSchedule.getId()))
                .as("활성 예약 둘이 함께 점유한 좌석").isZero();

        // 예약마다 받은 좌석 수가 매수와 같다 (부분 선점이 남지 않았다)
        assertThat(countOf("""
                SELECT count(*) FROM reservations r
                WHERE r.schedule_id = ? AND r.status IN ('CONFIRMED', 'PENDING')
                  AND (SELECT count(*) FROM reservation_seats rs WHERE rs.reservation_id = r.id)
                      <> r.quantity
                """, onSaleSchedule.getId()))
                .as("좌석 수와 매수가 어긋난 예약").isZero();

        // 좌석 가격의 합이 결제 금액과 같다
        assertThat(countOf("""
                SELECT count(*) FROM reservations r
                WHERE r.schedule_id = ?
                  AND EXISTS (SELECT 1 FROM reservation_seats rs WHERE rs.reservation_id = r.id)
                  AND r.total_amount <> (SELECT sum(rs.price) FROM reservation_seats rs
                                         WHERE rs.reservation_id = r.id)
                """, onSaleSchedule.getId()))
                .as("좌석 가격 합이 결제 금액과 어긋난 예약").isZero();

        // 한 예약의 좌석은 모두 같은 등급이다 ("한 예약 = 한 등급" 정책)
        assertThat(countOf("""
                SELECT count(*) FROM (
                    SELECT rs.reservation_id
                    FROM reservation_seats rs
                             JOIN schedule_seats ss ON ss.id = rs.schedule_seat_id
                             JOIN reservations r ON r.id = rs.reservation_id
                    WHERE r.schedule_id = ?
                    GROUP BY rs.reservation_id HAVING count(DISTINCT ss.ticket_grade_id) > 1
                ) mixed
                """, onSaleSchedule.getId()))
                .as("등급이 섞인 예약").isZero();
    }

    @Test
    @DisplayName("이 회차·등급에 없는 좌석을 고르면 404 NOT_FOUND를 반환한다")
    void 없는_좌석_선점_실패() throws Exception {
        MockHttpSession session = loginAsNewMember("booker6@tikkit.com");
        ReservationCreateRequest request = new ReservationCreateRequest(
                onSaleSchedule.getId(), onSaleGrade.getId(), 1, List.of(999_999L));

        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("PENDING 예약을 결제하면 200과 CONFIRMED를 받고, 결제 내역이 PAID 상태로 생성된다")
    void 결제_성공() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("payer1@tikkit.com");
        Long reservationId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), nextSeats(1));

        // when & then
        mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedAt").isNotEmpty());

        Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getMethod()).isEqualTo(PaymentMethod.CARD);
    }

    @Test
    @DisplayName("만료 시각이 지난 PENDING 예약을 결제하면 409 RESERVATION_EXPIRED를 반환한다")
    void 결제_실패_만료된_예약() throws Exception {
        // given: 배치가 아직 안 돌아 PENDING인 채로 expiresAt만 과거인 상황을 직접 만든다
        MockHttpSession session = loginAsNewMember("payer2@tikkit.com");
        Member member = memberRepository.findByEmail("payer2@tikkit.com").orElseThrow();
        Reservation expired = reservationRepository.save(Reservation.builder()
                .reservationNo("TK260101-999001")
                .member(member).schedule(onSaleSchedule).ticketGrade(onSaleGrade)
                .quantity(1).unitPrice(onSaleGrade.getPrice()).totalAmount(onSaleGrade.getPrice())
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build());

        // when & then
        mockMvc.perform(post("/api/v1/reservations/{id}/payments", expired.getId())
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));
    }

    @Test
    @DisplayName("이미 CONFIRMED인 예약을 다시 결제하면 409 INVALID_STATUS_TRANSITION을 반환한다")
    void 결제_실패_이미_확정된_예약() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("payer3@tikkit.com");
        Long reservationId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), nextSeats(1));
        mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
                .andExpect(status().isOk());

        // when & then
        mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("PENDING 예약을 취소하면 200 CANCELLED를 받고 좌석이 반환된다")
    void 취소_PENDING_좌석반환() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("canceller1@tikkit.com");
        Long reservationId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), nextSeats(2));
        int availableAfterReserve = availableSeatCount(onSaleGrade.getId());

        // when & then
        mockMvc.perform(post("/api/v1/reservations/{id}/cancel", reservationId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(availableSeatCount(onSaleGrade.getId()))
                .isEqualTo(availableAfterReserve + 2);
    }

    @Test
    @DisplayName("CONFIRMED 예약을 마감 전에 취소하면 결제가 REFUNDED로 바뀌고 좌석이 반환된다")
    void 취소_CONFIRMED_마감전_환불() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("canceller2@tikkit.com");
        Long reservationId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), nextSeats(1));
        mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
                .andExpect(status().isOk());
        clearPersistenceContext();
        int availableAfterPay = availableSeatCount(onSaleGrade.getId());

        // when & then
        mockMvc.perform(post("/api/v1/reservations/{id}/cancel", reservationId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(paymentRepository.findByReservationId(reservationId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(availableSeatCount(onSaleGrade.getId()))
                .isEqualTo(availableAfterPay + 1);
    }

    @Test
    @DisplayName("CONFIRMED 예약을 공연 24시간 전 이후 취소하면 409 CANCEL_DEADLINE_PASSED를 반환한다")
    void 취소_실패_마감후() throws Exception {
        // given: 공연 시작까지 24시간이 채 안 남은 회차를 별도로 만든다
        Instant now = Instant.now();
        Schedule soonSchedule = scheduleRepository.save(Schedule.builder()
                .performance(onSaleSchedule.getPerformance())
                .showAt(now.plus(12, ChronoUnit.HOURS))
                .bookingOpenAt(now.minus(1, ChronoUnit.DAYS))
                .bookingCloseAt(now.plus(11, ChronoUnit.HOURS))
                .build());
        TicketGrade soonGrade = ticketGradeRepository.save(TicketGrade.builder()
                .schedule(soonSchedule).grade(Grade.R).price(new BigDecimal("99000"))
                .build());
        MockHttpSession session = loginAsNewMember("canceller3@tikkit.com");
        Long reservationId = reserveViaApi(session, soonSchedule.getId(), soonGrade.getId(),
                createAvailableSeats(soonSchedule, soonGrade, "R-중", 1));
        mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
                .andExpect(status().isOk());
        clearPersistenceContext();

        // when & then
        mockMvc.perform(post("/api/v1/reservations/{id}/cancel", reservationId).session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_DEADLINE_PASSED"));
    }

    @Test
    @DisplayName("다른 회원 소유의 예약을 상세조회·결제·취소하려 하면 모두 404를 반환한다")
    void 다른회원_소유_예약_접근_404() throws Exception {
        // given
        MockHttpSession ownerSession = loginAsNewMember("owner@tikkit.com");
        Long reservationId = reserveViaApi(ownerSession, onSaleSchedule.getId(), onSaleGrade.getId(), nextSeats(1));
        MockHttpSession strangerSession = loginAsNewMember("stranger@tikkit.com");

        // when & then
        mockMvc.perform(get("/api/v1/reservations/{id}", reservationId).session(strangerSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(strangerSession)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/v1/reservations/{id}/cancel", reservationId).session(strangerSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("내 예매 목록은 본인 것만, 상태로 필터링해 조회된다")
    void 내예매목록_본인것만_상태필터() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("lister@tikkit.com");
        Long pendingId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), nextSeats(1));
        MockHttpSession otherSession = loginAsNewMember("otherLister@tikkit.com");
        reserveViaApi(otherSession, onSaleSchedule.getId(), onSaleGrade.getId(), nextSeats(1));

        // when & then: 본인 것만, PENDING만 필터링돼 1건만 조회된다
        mockMvc.perform(get("/api/v1/reservations").session(session).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].id").value(pendingId));
    }

    /** 회차에 예매 가능 좌석을 만들고 schedule_seats id 목록을 돌려준다. */
    private List<Long> createAvailableSeats(Schedule schedule, TicketGrade grade, String section, int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int seatNumber = 1; seatNumber <= count; seatNumber++) {
            Seat seat = seatRepository.save(Seat.builder()
                    .venue(venue).section(section).rowLabel("1").seatNumber(seatNumber)
                    .posX(seatNumber).posY(1).build());
            ids.add(scheduleSeatRepository.save(ScheduleSeat.builder()
                    .schedule(schedule).seat(seat).ticketGrade(grade)
                    .status(SeatStatus.AVAILABLE).build()).getId());
        }
        return ids;
    }

    /**
     * 아직 아무도 고르지 않은 좌석을 quantity개 집어 준다.
     * <p>
     * 호출마다 다른 좌석을 돌려줘야 한 테스트 안에서 여러 회원이 선점할 수 있다. 같은 좌석을 주면
     * 두 번째 요청이 SOLD_OUT으로 떨어져서, 검증하려던 것과 다른 이유로 테스트가 깨진다.
     */
    private List<Long> nextSeats(int quantity) {
        int from = seatCursor.getAndAdd(quantity);
        return onSaleSeatIds.subList(from, from + quantity);
    }

    private Long reserveViaApi(MockHttpSession session, Long scheduleId, Long ticketGradeId, List<Long> seatIds)
            throws Exception {
        ReservationCreateRequest request =
                new ReservationCreateRequest(scheduleId, ticketGradeId, seatIds.size(), seatIds);
        MvcResult result = mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    /**
     * 영속성 컨텍스트를 비워 다음 요청이 DB 상태를 새로 읽게 한다.
     * <p>
     * 이 테스트는 {@code @Transactional}이라 여러 HTTP 요청이 <b>영속성 컨텍스트 하나</b>를 공유한다.
     * 상태 전이가 조건부 UPDATE로 바뀐 뒤로는(Task 019) 벌크 UPDATE가 1차 캐시를 갱신하지 않아서,
     * 결제로 CONFIRMED가 된 예약을 이어서 취소하면 취소 쪽이 낡은 PENDING을 읽고 조건부 전이가 0행이 된다.
     * 운영에서는 요청마다 컨텍스트가 새로 생기므로 발생하지 않는 테스트 전용 문제다 — 그 조건을 맞춰준다.
     */
    private void clearPersistenceContext() {
        em.flush();
        em.clear();
    }

    /**
     * 잔여 수량을 DB에서 직접 읽는다.
     * <p>
     * 재고 차감이 벌크 UPDATE로 바뀐 뒤로는 {@code ticketGradeRepository.findById()}를 쓸 수 없다 (Task 019).
     * 이 테스트는 {@code @Transactional}이라 서비스와 영속성 컨텍스트를 공유하는데, 벌크 UPDATE는
     * 1차 캐시를 갱신하지 않으므로 {@code findById}가 {@code @BeforeEach}에서 올려둔 <b>낡은 인스턴스</b>를
     * 그대로 돌려준다. {@code JdbcTemplate}은 같은 트랜잭션의 커넥션을 쓰므로 미커밋 UPDATE까지 보인다.
     * <p>
     * {@code em.clear()}로 해결하지 않은 이유: {@code onSaleGrade}·{@code onSaleSchedule} 필드까지
     * detach되어 다른 단정이 지연 로딩에서 터진다.
     */
    /** 불변식 위반 건수를 세는 스칼라 조회. 0이 아니면 그 자체가 위반 목록의 크기다. */
    private int countOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private int availableSeatCount(Long ticketGradeId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM schedule_seats
                WHERE ticket_grade_id = ? AND status = 'AVAILABLE'
                """, Integer.class, ticketGradeId);
    }

    private MockHttpSession loginAsNewMember(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest(email, PASSWORD, "홍길동", "010-1111-2222"))))
                .andExpect(status().isOk());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) loginResult.getRequest().getSession(false);
    }
}
