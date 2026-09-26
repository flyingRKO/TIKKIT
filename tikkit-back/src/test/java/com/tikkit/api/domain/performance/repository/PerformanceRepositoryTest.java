package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.PerformanceSummaryResponse;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.VenueRepository;
import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class PerformanceRepositoryTest extends AbstractContainerTest {

    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private PerformanceRepository performanceRepository;

    private Venue venue;
    private Performance classicOnSale;
    private Performance concertOnSale;
    private Performance musicalUpcoming;
    private Performance sportsClosed;

    @BeforeEach
    void setUp() {
        venue = venueRepository.save(Venue.builder().name("테스트 공연장").address("서울").build());

        classicOnSale = performanceRepository.save(performance("판타지아 오케스트라", PerformanceCategory.CLASSIC,
                PerformanceStatus.ON_SALE, LocalDate.now().plusDays(5)));
        concertOnSale = performanceRepository.save(performance("록스타 라이브", PerformanceCategory.CONCERT,
                PerformanceStatus.ON_SALE, LocalDate.now().plusDays(10)));
        musicalUpcoming = performanceRepository.save(performance("그날의 약속", PerformanceCategory.MUSICAL,
                PerformanceStatus.UPCOMING, LocalDate.now().plusDays(20)));
        sportsClosed = performanceRepository.save(performance("챔피언스 매치", PerformanceCategory.SPORTS,
                PerformanceStatus.CLOSED, LocalDate.now().minusDays(5)));
    }

    @Test
    @DisplayName("카테고리로 필터링하면 해당 카테고리의 공연만 조회된다")
    void 카테고리_필터링() {
        Page<PerformanceSummaryResponse> result = performanceRepository.search(
                PerformanceCategory.CLASSIC, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(PerformanceSummaryResponse::title)
                .containsExactly("판타지아 오케스트라");
    }

    @Test
    @DisplayName("키워드로 제목을 부분검색한다")
    void 키워드_부분검색() {
        Page<PerformanceSummaryResponse> result = performanceRepository.search(
                null, "약속", null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(PerformanceSummaryResponse::title)
                .containsExactly("그날의 약속");
    }

    @Test
    @DisplayName("판매 상태로 필터링한다")
    void 판매상태_필터링() {
        Page<PerformanceSummaryResponse> result = performanceRepository.search(
                null, null, PerformanceStatus.ON_SALE, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(PerformanceSummaryResponse::title)
                .containsExactlyInAnyOrder("판타지아 오케스트라", "록스타 라이브");
    }

    @Test
    @DisplayName("필터가 없으면 전체를 startDate 오름차순으로, 페이지 크기만큼 나눠 조회한다")
    void 필터없음_전체조회_페이징_정렬() {
        Page<PerformanceSummaryResponse> firstPage = performanceRepository.search(
                null, null, null, PageRequest.of(0, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        // startDate 오름차순이므로 판매종료(가장 이전 날짜)가 가장 먼저 나온다
        assertThat(firstPage.getContent()).extracting(PerformanceSummaryResponse::title)
                .containsExactly("챔피언스 매치", "판타지아 오케스트라");

        Page<PerformanceSummaryResponse> secondPage = performanceRepository.search(
                null, null, null, PageRequest.of(1, 2));
        assertThat(secondPage.getContent()).extracting(PerformanceSummaryResponse::title)
                .containsExactly("록스타 라이브", "그날의 약속");
    }

    @Test
    @DisplayName("venueName은 join한 공연장 이름으로 채워진다")
    void venueName_조인_확인() {
        Page<PerformanceSummaryResponse> result = performanceRepository.search(
                PerformanceCategory.CLASSIC, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).venueName()).isEqualTo("테스트 공연장");
    }

    @Test
    @DisplayName("존재하는 id로 조회하면 venue가 함께 채워진 Performance를 반환한다")
    void id로_조회_venue_포함() {
        Optional<Performance> found = performanceRepository.findByIdWithVenue(classicOnSale.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getVenue().getName()).isEqualTo("테스트 공연장");
    }

    @Test
    @DisplayName("존재하지 않는 id로 조회하면 빈 Optional을 반환한다")
    void id로_조회_존재하지않음() {
        Optional<Performance> found = performanceRepository.findByIdWithVenue(999_999L);

        assertThat(found).isEmpty();
    }

    private Performance performance(String title, PerformanceCategory category, PerformanceStatus status,
                                      LocalDate startDate) {
        return Performance.builder()
                .title(title)
                .category(category)
                .venue(venue)
                .runningMinutes(120)
                .ageRating("전체 관람가")
                .startDate(startDate)
                .endDate(startDate)
                .status(status)
                .build();
    }
}
