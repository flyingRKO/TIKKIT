package com.tikkit.api.domain.performance.controller;

import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러까지 실제로 태우는 통합 테스트. 별도의 @WebMvcTest 컨트롤러 단독 테스트는 만들지 않는다.
 */
@AutoConfigureMockMvc
@Transactional
class PerformanceApiIntegrationTest extends AbstractContainerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;

    private Performance classicPerformance;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder().name("예술의전당").address("서울 서초구").build());
        classicPerformance = performanceRepository.save(Performance.builder()
                .title("판타지아 오케스트라")
                .category(PerformanceCategory.CLASSIC)
                .venue(venue)
                .runningMinutes(110)
                .ageRating("전체 관람가")
                .startDate(LocalDate.now().plusDays(5))
                .endDate(LocalDate.now().plusDays(5))
                .status(PerformanceStatus.ON_SALE)
                .build());
        Instant showAt = Instant.now().plus(5, ChronoUnit.DAYS);
        scheduleRepository.save(Schedule.builder()
                .performance(classicPerformance)
                .showAt(showAt)
                .bookingOpenAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .bookingCloseAt(showAt.minus(2, ChronoUnit.HOURS))
                .build());

        Venue otherVenue = venueRepository.save(Venue.builder().name("올림픽공원 KSPO돔").address("서울 송파구").build());
        performanceRepository.save(Performance.builder()
                .title("록스타 라이브")
                .category(PerformanceCategory.CONCERT)
                .venue(otherVenue)
                .runningMinutes(150)
                .ageRating("만 12세 이상")
                .startDate(LocalDate.now().plusDays(15))
                .endDate(LocalDate.now().plusDays(15))
                .status(PerformanceStatus.UPCOMING)
                .build());
    }

    @Test
    @DisplayName("목록 조회는 페이지 응답 형태로 전체 공연을 반환한다")
    void 목록_조회() throws Exception {
        mockMvc.perform(get("/api/v1/performances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(2));
    }

    @Test
    @DisplayName("category로 필터링하면 해당 카테고리만 반환한다")
    void 카테고리_필터_조회() throws Exception {
        mockMvc.perform(get("/api/v1/performances").param("category", "CLASSIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("판타지아 오케스트라"))
                .andExpect(jsonPath("$.data.content[0].venueName").value("예술의전당"));
    }

    @Test
    @DisplayName("category가 유효하지 않으면 400과 VALIDATION_ERROR를 반환한다")
    void 잘못된_카테고리는_400() throws Exception {
        mockMvc.perform(get("/api/v1/performances").param("category", "NOT_A_CATEGORY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("상세 조회는 회차 목록을 포함해 반환한다")
    void 상세_조회() throws Exception {
        mockMvc.perform(get("/api/v1/performances/{id}", classicPerformance.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("판타지아 오케스트라"))
                .andExpect(jsonPath("$.data.venueAddress").value("서울 서초구"))
                .andExpect(jsonPath("$.data.schedules.length()").value(1));
    }

    @Test
    @DisplayName("존재하지 않는 공연을 조회하면 404와 NOT_FOUND를 반환한다")
    void 존재하지않는_공연은_404() throws Exception {
        mockMvc.perform(get("/api/v1/performances/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
