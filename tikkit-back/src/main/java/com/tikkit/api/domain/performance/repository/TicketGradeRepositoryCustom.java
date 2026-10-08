package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.TicketGradeRow;

import java.util.List;

public interface TicketGradeRepositoryCustom {

    /**
     * 특정 회차의 등급별 가격을 가격 내림차순(VIP → R → S → A)으로 조회한다.
     * <p>
     * 잔여 수량은 들어 있지 않다. 지정석 전환 후 잔여 수량의 원천이 {@code schedule_seats}의
     * AVAILABLE 건수라서, {@code ScheduleSeatRepository.countAvailableByScheduleId}와 합쳐
     * {@code ScheduleService}가 응답을 조립한다.
     */
    List<TicketGradeRow> findRowsByScheduleId(Long scheduleId);
}
