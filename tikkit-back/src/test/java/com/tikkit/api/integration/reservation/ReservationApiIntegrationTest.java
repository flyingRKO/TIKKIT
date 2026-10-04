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
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.reservation.dto.PaymentRequest;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import com.tikkit.api.domain.venue.entity.Venue;
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
    private MemberRepository memberRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    private Schedule onSaleSchedule;
    private TicketGrade onSaleGrade;

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
                .totalQuantity(10)
                .remainingQuantity(3)
                .build());
    }

    @Test
    @DisplayName("예매 가능 기간에 로그인 후 선점하면 201과 PENDING 예약을 받고, 잔여 수량이 차감된다")
    void 선점_성공() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("booker1@tikkit.com");
        ReservationCreateRequest request =
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 2);

        // when & then
        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reservationNo").value(matchesPattern("^TK\\d{6}-\\d{6}$")))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

        assertThat(remainingOf(onSaleGrade.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 회원이 연속으로 선점하면 예약번호가 서로 다르다")
    void 예약번호_중복없음() throws Exception {
        // given
        MockHttpSession session1 = loginAsNewMember("booker2@tikkit.com");
        MockHttpSession session2 = loginAsNewMember("booker2b@tikkit.com");
        ReservationCreateRequest request =
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 1);

        // when
        MvcResult first = mockMvc.perform(post("/api/v1/reservations")
                        .session(session1)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/reservations")
                        .session(session2)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
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
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 1);

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
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 5);

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
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 1);

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
    @DisplayName("잔여 수량보다 많이 요청하면 409 SOLD_OUT을 반환한다")
    void 재고_부족_선점_실패() throws Exception {
        MockHttpSession session = loginAsNewMember("booker4@tikkit.com");
        ReservationCreateRequest request =
                new ReservationCreateRequest(onSaleSchedule.getId(), onSaleGrade.getId(), 4);

        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
    }

    @Test
    @DisplayName("PENDING 예약을 결제하면 200과 CONFIRMED를 받고, 결제 내역이 PAID 상태로 생성된다")
    void 결제_성공() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("payer1@tikkit.com");
        Long reservationId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), 1);

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
        Long reservationId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), 1);
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
    @DisplayName("PENDING 예약을 취소하면 200 CANCELLED를 받고 잔여 수량이 복원된다")
    void 취소_PENDING_재고복원() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("canceller1@tikkit.com");
        Long reservationId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), 2);
        // 차감은 벌크 UPDATE인데 복원은 아직 엔티티 더티체킹이라, 컨텍스트를 비워 취소 쪽이 DB 값을 읽게 한다.
        // 상태 전이·복원까지 조건부 UPDATE로 바꾸는 커밋에서 이 줄은 필요 없어진다.
        clearPersistenceContext();
        int remainingAfterReserve = remainingOf(onSaleGrade.getId());

        // when & then
        mockMvc.perform(post("/api/v1/reservations/{id}/cancel", reservationId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // 복원이 아직 엔티티 더티체킹이라 flush 전에는 UPDATE가 나가지 않는다 — JdbcTemplate이 보도록 비운다.
        clearPersistenceContext();
        assertThat(remainingOf(onSaleGrade.getId()))
                .isEqualTo(remainingAfterReserve + 2);
    }

    @Test
    @DisplayName("CONFIRMED 예약을 마감 전에 취소하면 결제가 REFUNDED로 바뀌고 잔여 수량이 복원된다")
    void 취소_CONFIRMED_마감전_환불() throws Exception {
        // given
        MockHttpSession session = loginAsNewMember("canceller2@tikkit.com");
        Long reservationId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), 1);
        mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
                .andExpect(status().isOk());
        clearPersistenceContext();
        int remainingAfterPay = remainingOf(onSaleGrade.getId());

        // when & then
        mockMvc.perform(post("/api/v1/reservations/{id}/cancel", reservationId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(paymentRepository.findByReservationId(reservationId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(remainingOf(onSaleGrade.getId()))
                .isEqualTo(remainingAfterPay + 1);
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
                .totalQuantity(5).remainingQuantity(5).build());
        MockHttpSession session = loginAsNewMember("canceller3@tikkit.com");
        Long reservationId = reserveViaApi(session, soonSchedule.getId(), soonGrade.getId(), 1);
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
        Long reservationId = reserveViaApi(ownerSession, onSaleSchedule.getId(), onSaleGrade.getId(), 1);
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
        Long pendingId = reserveViaApi(session, onSaleSchedule.getId(), onSaleGrade.getId(), 1);
        MockHttpSession otherSession = loginAsNewMember("otherLister@tikkit.com");
        reserveViaApi(otherSession, onSaleSchedule.getId(), onSaleGrade.getId(), 1);

        // when & then: 본인 것만, PENDING만 필터링돼 1건만 조회된다
        mockMvc.perform(get("/api/v1/reservations").session(session).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].id").value(pendingId));
    }

    private Long reserveViaApi(MockHttpSession session, Long scheduleId, Long ticketGradeId, int quantity) throws Exception {
        ReservationCreateRequest request = new ReservationCreateRequest(scheduleId, ticketGradeId, quantity);
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
    private int remainingOf(Long ticketGradeId) {
        return jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM ticket_grades WHERE id = ?", Integer.class, ticketGradeId);
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
