package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.entity.TicketGrade;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 등급 조회 전용이 됐다.
 * <p>
 * 지정석 전환 전에는 여기에 재고를 깎고 되돌리는 조건부 UPDATE
 * ({@code decreaseRemainingQuantity} / {@code increaseRemainingQuantity})가 있었다. 재고의 원천이
 * {@code ticket_grades}의 수량 컬럼에서 {@code schedule_seats}의 좌석 행으로 넘어가면서
 * {@code ScheduleSeatRepository}의 선점·반환 쿼리가 그 역할을 대신한다 (Task 022).
 */
public interface TicketGradeRepository extends JpaRepository<TicketGrade, Long>, TicketGradeRepositoryCustom {
}
