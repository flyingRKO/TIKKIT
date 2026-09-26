package com.tikkit.api.domain.performance.controller;

import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.VenueRepository;
import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class ScheduleApiIntegrationTest extends AbstractContainerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private TicketGradeRepository ticketGradeRepository;

    private Schedule schedule;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder().name("테스트 공연장").address("서울").build());
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
        ticketGradeRepository.save(TicketGrade.builder()
                .schedule(schedule).grade(Grade.S).price(new BigDecimal("66000"))
                .totalQuantity(120).remainingQuantity(120).build());
        ticketGradeRepository.save(TicketGrade.builder()
                .schedule(schedule).grade(Grade.VIP).price(new BigDecimal("150000"))
                .totalQuantity(30).remainingQuantity(30).build());
    }

    @Test
    @DisplayName("등급별 가격·잔여 수량을 가격 내림차순으로 반환한다")
    void 등급_조회() throws Exception {
        mockMvc.perform(get("/api/v1/schedules/{id}/ticket-grades", schedule.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].grade").value("VIP"))
                .andExpect(jsonPath("$.data[0].remainingQuantity").value(30))
                .andExpect(jsonPath("$.data[1].grade").value("S"));
    }

    @Test
    @DisplayName("존재하지 않는 회차를 조회하면 404와 NOT_FOUND를 반환한다")
    void 존재하지않는_회차는_404() throws Exception {
        mockMvc.perform(get("/api/v1/schedules/{id}/ticket-grades", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
