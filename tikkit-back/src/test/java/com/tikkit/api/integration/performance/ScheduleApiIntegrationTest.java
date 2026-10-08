package com.tikkit.api.integration.performance;

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
import com.tikkit.api.domain.venue.entity.Seat;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.SeatRepository;
import com.tikkit.api.domain.venue.repository.VenueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class ScheduleApiIntegrationTest extends AbstractContainerTest {

    /**
     * 등급 컬럼에 적어둘 수량. 좌석 수와 <b>일부러 다르게</b> 둬서, 응답의 잔여 수량이
     * {@code ticket_grades}의 컬럼이 아니라 {@code schedule_seats}의 AVAILABLE 건수에서
     * 나온다는 걸 단정으로 구분할 수 있게 한다 (Task 022).
     */
    private static final int STALE_COLUMN_QUANTITY = 30;
    private static final int VIP_SEAT_COUNT = 4;
    private static final int S_SEAT_COUNT = 3;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private TicketGradeRepository ticketGradeRepository;
    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    private Venue venue;
    private Schedule schedule;

    @BeforeEach
    void setUp() {
        venue = venueRepository.save(Venue.builder().name("테스트 공연장").address("서울").build());
        Performance performance = performanceRepository.save(Performance.builder()
                .title("테스트 공연").category(PerformanceCategory.CONCERT).venue(venue)
                .runningMinutes(120).ageRating("전체 관람가").status(PerformanceStatus.ON_SALE).build());
        Instant showAt = Instant.now().plus(10, ChronoUnit.DAYS);
        schedule = scheduleRepository.save(Schedule.builder()
                .performance(performance)
                .showAt(showAt)
                .bookingOpenAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .bookingCloseAt(showAt.minus(2, ChronoUnit.HOURS))
                .build());

        TicketGrade sGrade = ticketGradeRepository.save(TicketGrade.builder()
                .schedule(schedule).grade(Grade.S).price(new BigDecimal("66000"))
                .build());
        TicketGrade vipGrade = ticketGradeRepository.save(TicketGrade.builder()
                .schedule(schedule).grade(Grade.VIP).price(new BigDecimal("150000"))
                .build());

        createAvailableSeats(vipGrade, "VIP-중", VIP_SEAT_COUNT, 1);
        createAvailableSeats(sGrade, "S-중", S_SEAT_COUNT, 2);
    }

    @Test
    @DisplayName("잔여 수량은 등급 컬럼이 아니라 예매 가능 좌석 수에서 나온다")
    void 등급_조회() throws Exception {
        mockMvc.perform(get("/api/v1/schedules/{id}/ticket-grades", schedule.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].grade").value("VIP"))
                // 컬럼에는 30이 들어 있지만 좌석은 4석이다 — 4가 나와야 파생이 동작한 것이다.
                .andExpect(jsonPath("$.data[0].remainingQuantity").value(VIP_SEAT_COUNT))
                .andExpect(jsonPath("$.data[1].grade").value("S"))
                .andExpect(jsonPath("$.data[1].remainingQuantity").value(S_SEAT_COUNT));
    }

    @Test
    @DisplayName("존재하지 않는 회차를 조회하면 404와 NOT_FOUND를 반환한다")
    void 존재하지않는_회차는_404() throws Exception {
        mockMvc.perform(get("/api/v1/schedules/{id}/ticket-grades", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("좌석 배치도는 회차의 모든 좌석을 앞열 → 왼쪽 순서로 평면 배열로 반환한다")
    void 좌석_배치도_조회() throws Exception {
        mockMvc.perform(get("/api/v1/schedules/{id}/seats", schedule.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(VIP_SEAT_COUNT + S_SEAT_COUNT))
                // VIP석은 posY 1, S석은 posY 2로 깔았으므로 VIP가 먼저 온다
                .andExpect(jsonPath("$.data[0].section").value("VIP-중"))
                .andExpect(jsonPath("$.data[0].rowLabel").value("1"))
                .andExpect(jsonPath("$.data[0].seatNumber").value(1))
                .andExpect(jsonPath("$.data[0].posX").value(1))
                .andExpect(jsonPath("$.data[0].posY").value(1))
                .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[0].ticketGradeId").isNumber())
                .andExpect(jsonPath("$.data[%d].section".formatted(VIP_SEAT_COUNT)).value("S-중"));
    }

    @Test
    @DisplayName("존재하지 않는 회차의 좌석을 조회하면 404와 NOT_FOUND를 반환한다")
    void 존재하지않는_회차_좌석은_404() throws Exception {
        mockMvc.perform(get("/api/v1/schedules/{id}/seats", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("좌석 배치도의 id는 물리 좌석이 아니라 회차 좌석 id다 — 선점 요청의 seatIds가 이 값이다")
    void 좌석_배치도_id는_회차좌석_id() throws Exception {
        List<Long> scheduleSeatIds = jdbcTemplate.queryForList(
                "SELECT id FROM schedule_seats WHERE schedule_id = ? ORDER BY id",
                Long.class, schedule.getId());

        MvcResult result = mockMvc.perform(get("/api/v1/schedules/{id}/seats", schedule.getId()))
                .andExpect(status().isOk())
                .andReturn();

        List<Long> responseIds = objectMapper
                .readTree(result.getResponse().getContentAsString()).path("data")
                .findValuesAsText("id").stream().map(Long::valueOf).sorted().toList();
        assertThat(responseIds).isEqualTo(scheduleSeatIds);
    }

    private void createAvailableSeats(TicketGrade ticketGrade, String section, int count, int posY) {
        for (int seatNumber = 1; seatNumber <= count; seatNumber++) {
            Seat seat = seatRepository.save(Seat.builder()
                    .venue(venue).section(section).rowLabel(String.valueOf(posY))
                    .seatNumber(seatNumber).posX(seatNumber).posY(posY).build());
            scheduleSeatRepository.save(ScheduleSeat.builder()
                    .schedule(schedule).seat(seat).ticketGrade(ticketGrade)
                    .status(SeatStatus.AVAILABLE).build());
        }
    }
}
