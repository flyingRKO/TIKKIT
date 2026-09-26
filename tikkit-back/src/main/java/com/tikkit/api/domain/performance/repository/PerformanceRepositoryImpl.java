package com.tikkit.api.domain.performance.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tikkit.api.domain.performance.dto.PerformanceSummaryResponse;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.QPerformance;
import com.tikkit.api.domain.venue.entity.QVenue;
import com.querydsl.core.types.Projections;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class PerformanceRepositoryImpl implements PerformanceRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    private static final QPerformance performance = QPerformance.performance;
    private static final QVenue venue = QVenue.venue;

    @Override
    public Page<PerformanceSummaryResponse> search(PerformanceCategory category, String keyword,
                                                     PerformanceStatus status, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder()
                .and(categoryEq(category))
                .and(titleContains(keyword))
                .and(statusEq(status));

        // 정렬은 startDate asc — performances(status, start_date) 인덱스를 의식한 것. 세부 실행계획 검증은 Task 028에서.
        List<PerformanceSummaryResponse> content = queryFactory
                .select(Projections.constructor(PerformanceSummaryResponse.class,
                        performance.id, performance.title, performance.category, performance.posterUrl,
                        venue.name, performance.status, performance.startDate, performance.endDate))
                .from(performance)
                .join(performance.venue, venue)
                .where(where)
                .orderBy(performance.startDate.asc(), performance.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> queryFactory
                .select(performance.count())
                .from(performance)
                .where(where)
                .fetchOne());
    }

    @Override
    public Optional<Performance> findByIdWithVenue(Long id) {
        Performance result = queryFactory
                .selectFrom(performance)
                .join(performance.venue, venue).fetchJoin()
                .where(performance.id.eq(id))
                .fetchOne();
        return Optional.ofNullable(result);
    }

    private BooleanExpression categoryEq(PerformanceCategory category) {
        return category != null ? performance.category.eq(category) : null;
    }

    private BooleanExpression titleContains(String keyword) {
        return (keyword != null && !keyword.isBlank()) ? performance.title.containsIgnoreCase(keyword) : null;
    }

    private BooleanExpression statusEq(PerformanceStatus status) {
        return status != null ? performance.status.eq(status) : null;
    }
}
