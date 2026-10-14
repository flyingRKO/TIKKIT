package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.TicketGradeRow;
import com.tikkit.api.domain.performance.entity.TicketGrade;

import java.util.List;
import java.util.Optional;

public interface TicketGradeRepositoryCustom {

    /**
     * 특정 회차의 등급별 가격을 가격 내림차순(VIP → R → S → A)으로 조회한다.
     * <p>
     * 잔여 수량은 들어 있지 않다. 지정석 전환 후 잔여 수량의 원천이 {@code schedule_seats}의
     * AVAILABLE 건수라서, {@code ScheduleSeatRepository.countAvailableByScheduleId}와 합쳐
     * {@code ScheduleService}가 응답을 조립한다.
     */
    List<TicketGradeRow> findRowsByScheduleId(Long scheduleId);

    /**
     * 등급을 소속 회차와 함께 한 문장으로 조회한다 (Task 028).
     * <p>
     * 예매 선점은 등급을 읽은 직후 회차의 판매 기간({@code bookingOpenAt}/{@code bookingCloseAt})을
     * 확인한다. {@code findById}로 등급만 가져오면 그 시점에 LAZY 프록시가 초기화되면서
     * {@code schedules} SELECT가 한 번 더 나간다 — 가장 뜨거운 쓰기 경로에서 왕복 1회다.
     * <p>
     * 회차를 함께 받아도 호출부가 쓰는 건 판매 기간 확인뿐이고, 엔티티를 수정하지 않는다
     * ({@code ReservationService}가 더티체킹으로 조건부 UPDATE를 덮어쓰지 않도록 지키는 규칙).
     */
    Optional<TicketGrade> findByIdWithSchedule(Long id);
}
