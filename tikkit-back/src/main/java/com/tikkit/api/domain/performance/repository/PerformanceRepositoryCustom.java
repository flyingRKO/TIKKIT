package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.PerformanceSummaryResponse;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface PerformanceRepositoryCustom {

    /**
     * 카테고리/키워드(제목 포함검색)/판매상태로 공연 목록을 조회한다. 세 조건 모두 null이면 전체 조회.
     */
    Page<PerformanceSummaryResponse> search(PerformanceCategory category, String keyword,
                                             PerformanceStatus status, Pageable pageable);

    /**
     * venue를 fetch join으로 함께 조회해 이후 lazy 추가 쿼리가 안 나가게 한다.
     */
    Optional<Performance> findByIdWithVenue(Long id);
}
