package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.ScheduleSummaryResponse;

import java.util.List;

public interface ScheduleRepositoryCustom {

    /**
     * 특정 공연에 속한 회차 목록을 공연일시(showAt) 오름차순으로 조회한다.
     */
    List<ScheduleSummaryResponse> findSummariesByPerformanceId(Long performanceId);
}
