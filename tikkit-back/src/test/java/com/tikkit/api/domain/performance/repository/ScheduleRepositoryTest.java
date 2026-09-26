package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.ScheduleSummaryResponse;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.VenueRepository;
import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ScheduleRepositoryTest extends AbstractContainerTest {

    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;

    private Performance performance;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder().name("테스트 공연장").address("서울").build());
        performance = performanceRepository.save(Performance.builder()
                .title("테스트 공연")
                .category(PerformanceCategory.CONCERT)
                .venue(venue)
                .runningMinutes(120)
                .ageRating("전체 관람가")
                .status(PerformanceStatus.ON_SALE)
                .build());
    }

    @Test
    @DisplayName("공연에 속한 회차만, showAt 오름차순으로 조회한다")
    void 회차_목록_조회_정렬_및_필터() {
        // given: 순서를 뒤섞어 저장해도 showAt 기준으로 정렬돼 나오는지 확인
        Schedule later = schedule(performance, 20);
        Schedule earlier = schedule(performance, 5);
        scheduleRepository.save(later);
        scheduleRepository.save(earlier);

        // 다른 공연의 회차는 결과에 섞이면 안 된다
        Venue otherVenue = venueRepository.save(Venue.builder().name("다른 공연장").address("부산").build());
        Performance otherPerformance = performanceRepository.save(Performance.builder()
                .title("다른 공연").category(PerformanceCategory.THEATER).venue(otherVenue)
                .runningMinutes(100).ageRating("전체 관람가").status(PerformanceStatus.ON_SALE).build());
        scheduleRepository.save(schedule(otherPerformance, 1));

        // when
        List<ScheduleSummaryResponse> result = scheduleRepository.findSummariesByPerformanceId(performance.getId());

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(earlier.getId());
        assertThat(result.get(1).id()).isEqualTo(later.getId());
    }

    @Test
    @DisplayName("회차가 없는 공연을 조회하면 빈 목록을 반환한다")
    void 회차_없음() {
        List<ScheduleSummaryResponse> result = scheduleRepository.findSummariesByPerformanceId(performance.getId());

        assertThat(result).isEmpty();
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
}
