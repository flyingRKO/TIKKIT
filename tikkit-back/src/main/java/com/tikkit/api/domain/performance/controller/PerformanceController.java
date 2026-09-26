package com.tikkit.api.domain.performance.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.common.response.PageResponse;
import com.tikkit.api.domain.performance.dto.PerformanceDetailResponse;
import com.tikkit.api.domain.performance.dto.PerformanceSummaryResponse;
import com.tikkit.api.domain.performance.service.PerformanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Performance", description = "공연·회차 조회")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PerformanceController {

    private final PerformanceService performanceService;

    @Operation(summary = "공연 목록 조회")
    @GetMapping("/performances")
    public ApiResponse<PageResponse<PerformanceSummaryResponse>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(new PageResponse<>(performanceService.list(category, keyword, status, page, size)));
    }

    @Operation(summary = "공연 상세 조회 (회차 목록 포함)")
    @GetMapping("/performances/{id}")
    public ApiResponse<PerformanceDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(performanceService.getDetail(id));
    }
}
