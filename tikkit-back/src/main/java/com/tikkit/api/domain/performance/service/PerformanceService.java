package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.dto.PerformanceDetailResponse;
import com.tikkit.api.domain.performance.dto.PerformanceListPage;
import com.tikkit.api.domain.performance.dto.PerformanceSummaryResponse;
import com.tikkit.api.domain.performance.dto.ScheduleSummaryResponse;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
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

    /**
     * 공연 목록을 조회한다.
     *
     * <h2>Task 029: 캐시</h2>
     * 키에서 {@code keyword}를 뺀 이유는 {@code condition}에 있다 — <b>keyword 검색은 캐시하지
     * 않는다.</b> 사용자가 아무 문자열이나 넣을 수 있어서 키 공간이 무한하고, 그대로 캐시하면
     * 한 번 쓰이고 버려지는 항목으로 캐시가 채워진다. 그래서 keyword가 있으면 캐시를 통과하고,
     * 없을 때만 {@code category|status|page|size}를 키로 쓴다.
     * <p>
     * 필터가 {@code null}일 때 Elvis({@code ?:})로 {@code '-'}를 넣는다. SpEL에서
     * {@code null + '|'}이 어떤 문자열이 되는지에 기대면 안 된다 — 실제로 그렇게 짰다가
     * 캐시가 기대한 키로 안 들어가서 테스트가 깨졌다. {@code '-'}는 enum 값에 나올 수 없는
     * 문자라서 {@code category=null}과 특정 카테고리가 같은 키로 뭉칠 수 없다.
     * <p>
     * 반환형이 {@code Page}가 아닌 이유는 {@link PerformanceListPage} Javadoc에 있다 —
     * {@code PageImpl}은 Jackson 역직렬화가 안 돼서 캐시에 넣을 수 없다.
     */
    @Cacheable(cacheNames = CacheNames.PERFORMANCE_LIST,
            key = "(#category ?: '-') + '|' + (#status ?: '-') + '|' + #page + '|' + #size",
            condition = "#keyword == null || #keyword.isBlank()")
    public PerformanceListPage list(String category, String keyword, String status, int page, int size) {
        PerformanceCategory categoryFilter = parseEnum(category, PerformanceCategory.class, "category");
        PerformanceStatus statusFilter = parseEnum(status, PerformanceStatus.class, "status");
        Pageable pageable = PageRequest.of(page, size);
        Page<PerformanceSummaryResponse> result =
                performanceRepository.search(categoryFilter, keyword, statusFilter, pageable);
        return new PerformanceListPage(result.getContent(), result.getTotalElements());
    }

    /**
     * 공연 상세(회차 목록 포함)를 조회한다.
     *
     * <p>Task 029: 키는 공연 id다. 없는 공연은 {@code BusinessException}이 나가므로 캐시에 남지
     * 않는다 — 예외가 던져지면 {@code @Cacheable}은 아무것도 저장하지 않는다. 그래서
     * {@code CatalogCache}가 쓴 {@code unless}가 여기서는 필요 없다.
     */
    @Cacheable(cacheNames = CacheNames.PERFORMANCE_DETAIL, key = "#id")
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
