package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.dto.PerformanceDetailResponse;
import com.tikkit.api.domain.performance.dto.PerformanceSummaryResponse;
import com.tikkit.api.domain.performance.dto.ScheduleSummaryResponse;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.venue.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PerformanceServiceTest {

    @Mock
    private PerformanceRepository performanceRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @InjectMocks
    private PerformanceService performanceService;

    @Test
    @DisplayName("category 값이 유효하지 않으면 VALIDATION_ERROR 예외를 던진다")
    void 잘못된_category_값() {
        assertThatThrownBy(() -> performanceService.list("NOT_A_CATEGORY", null, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("status 값이 유효하지 않으면 VALIDATION_ERROR 예외를 던진다")
    void 잘못된_status_값() {
        assertThatThrownBy(() -> performanceService.list(null, null, "NOT_A_STATUS", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("category/status 문자열을 enum으로 변환해 레포지토리에 전달한다")
    void 유효한_필터는_enum으로_변환되어_전달된다() {
        // given
        Page<PerformanceSummaryResponse> emptyPage = new PageImpl<>(List.of());
        given(performanceRepository.search(any(), any(), any(), any())).willReturn(emptyPage);

        // when
        performanceService.list("CONCERT", "락", "ON_SALE", 1, 10);

        // then
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(performanceRepository).search(eq(PerformanceCategory.CONCERT), eq("락"),
                eq(PerformanceStatus.ON_SALE), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(1, 10));
    }

    @Test
    @DisplayName("빈 문자열 필터는 조건 없음(null)으로 취급한다")
    void 빈_문자열_필터는_무시된다() {
        // given
        given(performanceRepository.search(isNull(), any(), isNull(), any())).willReturn(new PageImpl<>(List.of()));

        // when
        performanceService.list("", null, "  ", 0, 20);

        // then
        verify(performanceRepository).search(isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("존재하지 않는 공연을 조회하면 NOT_FOUND 예외를 던진다")
    void 존재하지_않는_공연_조회() {
        // given
        given(performanceRepository.findByIdWithVenue(anyLong())).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> performanceService.getDetail(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("공연 상세 조회 시 venue 정보와 회차 목록을 함께 조립한다")
    void 공연_상세_조회_성공() {
        // given
        Venue venue = Venue.builder().name("예술의전당").address("서울 서초구").build();
        Performance performance = Performance.builder()
                .title("판타지아 오케스트라").category(PerformanceCategory.CLASSIC).description("설명")
                .posterUrl(null).venue(venue).runningMinutes(110).ageRating("전체 관람가")
                .startDate(LocalDate.now()).endDate(LocalDate.now().plusDays(3)).status(PerformanceStatus.ON_SALE)
                .build();
        List<ScheduleSummaryResponse> schedules = List.of(
                new ScheduleSummaryResponse(1L, null, null, null));
        given(performanceRepository.findByIdWithVenue(1L)).willReturn(Optional.of(performance));
        given(scheduleRepository.findSummariesByPerformanceId(1L)).willReturn(schedules);

        // when
        PerformanceDetailResponse response = performanceService.getDetail(1L);

        // then
        assertThat(response.title()).isEqualTo("판타지아 오케스트라");
        assertThat(response.venueName()).isEqualTo("예술의전당");
        assertThat(response.venueAddress()).isEqualTo("서울 서초구");
        assertThat(response.schedules()).isEqualTo(schedules);
    }
}
