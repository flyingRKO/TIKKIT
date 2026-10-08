package com.tikkit.api.domain.performance.dto;

import com.tikkit.api.domain.performance.entity.Grade;

import java.math.BigDecimal;

/**
 * 등급의 가격 정보만 담은 내부 프로젝션. 잔여 수량은 들어 있지 않다.
 * <p>
 * 지정석 전환 후 잔여 수량의 원천이 {@code ticket_grades}의 컬럼에서
 * {@code schedule_seats}의 AVAILABLE 건수로 바뀌었다. 한 쿼리에서 집계까지 하려면
 * {@code LEFT JOIN} + {@code GROUP BY}에 좌석이 없는 등급의 null 처리와 SUM 반환 타입까지
 * 얽히는데, 등급 목록과 좌석 COUNT를 따로 조회해 {@code ScheduleService}에서 합치면
 * 두 쿼리가 각각 단순해진다. 그 조립 전 단계가 이 record다.
 */
public record TicketGradeRow(
        Long id,
        Grade grade,
        BigDecimal price
) {
}
