package com.tikkit.api.domain.performance.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.entity.QTicketGrade;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class TicketGradeRepositoryImpl implements TicketGradeRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    private static final QTicketGrade ticketGrade = QTicketGrade.ticketGrade;

    @Override
    public List<TicketGradeResponse> findResponsesByScheduleId(Long scheduleId) {
        return queryFactory
                .select(Projections.constructor(TicketGradeResponse.class,
                        ticketGrade.id, ticketGrade.grade, ticketGrade.price, ticketGrade.remainingQuantity))
                .from(ticketGrade)
                .where(ticketGrade.schedule.id.eq(scheduleId))
                // grade는 문자열로 매핑돼 있어 이름 기준 정렬(A,R,S,VIP)이 안 되므로 price 내림차순으로 대신한다.
                .orderBy(ticketGrade.price.desc())
                .fetch();
    }
}
