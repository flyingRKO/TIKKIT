package com.tikkit.api.domain.performance.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tikkit.api.domain.performance.dto.ScheduleSummaryResponse;
import com.tikkit.api.domain.performance.entity.QSchedule;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class ScheduleRepositoryImpl implements ScheduleRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    private static final QSchedule schedule = QSchedule.schedule;

    @Override
    public List<ScheduleSummaryResponse> findSummariesByPerformanceId(Long performanceId) {
        return queryFactory
                .select(Projections.constructor(ScheduleSummaryResponse.class,
                        schedule.id, schedule.showAt, schedule.bookingOpenAt, schedule.bookingCloseAt))
                .from(schedule)
                .where(schedule.performance.id.eq(performanceId))
                .orderBy(schedule.showAt.asc())
                .fetch();
    }
}
