package com.tikkit.api.domain.performance.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.common.response.PageResponse;
import com.tikkit.api.domain.performance.dto.PerformanceDetailResponse;
import com.tikkit.api.domain.performance.dto.PerformanceSummaryResponse;
import com.tikkit.api.domain.performance.dto.ScheduleSummaryResponse;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Tag(name = "Performance", description = "공연·회차 조회")
@RestController
@RequestMapping("/api/v1")
public class PerformanceController {

    @Operation(summary = "공연 목록 조회")
    @GetMapping("/performances")
    public ApiResponse<PageResponse<PerformanceSummaryResponse>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // TODO(Task 009): QueryDSL 기반 카테고리/키워드/상태 필터 + 페이징 조회 연결
        PerformanceSummaryResponse dummy = new PerformanceSummaryResponse(
                1L, "판타지아 오케스트라", PerformanceCategory.CLASSIC, null, "예술의전당 오페라극장",
                PerformanceStatus.ON_SALE, LocalDate.now().plusDays(20), LocalDate.now().plusDays(23));
        PageImpl<PerformanceSummaryResponse> dummyPage =
                new PageImpl<>(List.of(dummy), PageRequest.of(page, size), 1);
        return ApiResponse.success(new PageResponse<>(dummyPage));
    }

    @Operation(summary = "공연 상세 조회 (회차 목록 포함)")
    @GetMapping("/performances/{id}")
    public ApiResponse<PerformanceDetailResponse> detail(@PathVariable Long id) {
        // TODO(Task 009): 실제 공연 + 회차 목록 조회 로직 연결
        List<ScheduleSummaryResponse> schedules = List.of(
                new ScheduleSummaryResponse(1L, Instant.now().plus(20, ChronoUnit.DAYS),
                        Instant.now().minus(3, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS))
        );
        PerformanceDetailResponse dummy = new PerformanceDetailResponse(
                id, "판타지아 오케스트라", PerformanceCategory.CLASSIC, "국내 정상급 필하모닉이 선사하는 클래식 명곡 갈라 콘서트", null,
                "예술의전당 오페라극장", "서울특별시 서초구 남부순환로 2406", 110, "전체 관람가",
                PerformanceStatus.ON_SALE, schedules);
        return ApiResponse.success(dummy);
    }
}
