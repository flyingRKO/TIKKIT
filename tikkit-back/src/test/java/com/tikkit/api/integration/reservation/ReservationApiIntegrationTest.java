package com.tikkit.api.integration.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkit.api.domain.member.dto.LoginRequest;
import com.tikkit.api.domain.member.dto.SignupRequest;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.VenueRepository;
import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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

        TicketGrade reloaded = ticketGradeRepository.findById(onSaleGrade.getId()).orElseThrow();
        assertThat(reloaded.getRemainingQuantity()).isEqualTo(1);
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
