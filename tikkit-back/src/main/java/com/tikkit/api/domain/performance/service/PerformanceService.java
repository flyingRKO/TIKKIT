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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceService {

    private final PerformanceRepository performanceRepository;
    private final ScheduleRepository scheduleRepository;

    public Page<PerformanceSummaryResponse> list(String category, String keyword, String status, int page, int size) {
        PerformanceCategory categoryFilter = parseEnum(category, PerformanceCategory.class, "category");
        PerformanceStatus statusFilter = parseEnum(status, PerformanceStatus.class, "status");
        Pageable pageable = PageRequest.of(page, size);
        return performanceRepository.search(categoryFilter, keyword, statusFilter, pageable);
    }

    public PerformanceDetailResponse getDetail(Long id) {
        Performance performance = performanceRepository.findByIdWithVenue(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        List<ScheduleSummaryResponse> schedules = scheduleRepository.findSummariesByPerformanceId(id);

        return new PerformanceDetailResponse(
                performance.getId(), performance.getTitle(), performance.getCategory(), performance.getDescription(),
                performance.getPosterUrl(), performance.getVenue().getName(), performance.getVenue().getAddress(),
                performance.getRunningMinutes(), performance.getAgeRating(), performance.getStatus(), schedules);
    }

    /**
     * 비어있으면 필터 없음(null)으로 취급하고, 값이 있는데 enum과 매칭되지 않으면 400으로 응답한다.
     */
    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "유효하지 않은 " + fieldName + " 값입니다: " + value);
        }
    }
}
