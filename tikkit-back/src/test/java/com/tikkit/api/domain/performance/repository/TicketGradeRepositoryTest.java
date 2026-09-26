package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.VenueRepository;
import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class TicketGradeRepositoryTest extends AbstractContainerTest {

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
        schedule = scheduleRepository.save(schedule(performance, 10));
    }

    @Test
    @DisplayName("회차의 등급을 가격 내림차순(VIP → R → S)으로 조회한다")
    void 등급_가격_내림차순_정렬() {
        // given: 저장 순서를 가격과 다르게 뒤섞는다
        ticketGradeRepository.save(ticketGrade(schedule, Grade.S, "66000", 120));
        ticketGradeRepository.save(ticketGrade(schedule, Grade.VIP, "150000", 30));
        ticketGradeRepository.save(ticketGrade(schedule, Grade.R, "99000", 80));

        // when
        List<TicketGradeResponse> result = ticketGradeRepository.findResponsesByScheduleId(schedule.getId());

        // then
        assertThat(result).extracting(TicketGradeResponse::grade)
                .containsExactly(Grade.VIP, Grade.R, Grade.S);
    }

    @Test
    @DisplayName("다른 회차의 등급은 섞이지 않는다")
    void 다른_회차_등급_제외() {
        // given
        ticketGradeRepository.save(ticketGrade(schedule, Grade.VIP, "150000", 30));
        Schedule otherSchedule = scheduleRepository.save(schedule(schedule.getPerformance(), 20));
        ticketGradeRepository.save(ticketGrade(otherSchedule, Grade.R, "99000", 80));

        // when
        List<TicketGradeResponse> result = ticketGradeRepository.findResponsesByScheduleId(schedule.getId());

        // then
        assertThat(result).extracting(TicketGradeResponse::grade).containsExactly(Grade.VIP);
    }

    private Schedule schedule(Performance performance, int daysFromNow) {
        Instant showAt = Instant.now().plus(daysFromNow, ChronoUnit.DAYS);
        return Schedule.builder()
                .performance(performance)
                .showAt(showAt)
                .bookingOpenAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .bookingCloseAt(showAt.minus(2, ChronoUnit.HOURS))
                .build();
    }

    private TicketGrade ticketGrade(Schedule schedule, Grade grade, String price, int quantity) {
        return TicketGrade.builder()
                .schedule(schedule)
                .grade(grade)
                .price(new BigDecimal(price))
                .totalQuantity(quantity)
                .remainingQuantity(quantity)
                .build();
    }
}
