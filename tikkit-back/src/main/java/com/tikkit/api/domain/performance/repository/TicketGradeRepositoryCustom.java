package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.TicketGradeResponse;

import java.util.List;

public interface TicketGradeRepositoryCustom {

    /**
     * 특정 회차의 등급별 가격·잔여 수량을 가격 내림차순(VIP → R → S → A)으로 조회한다.
     */
    List<TicketGradeResponse> findResponsesByScheduleId(Long scheduleId);
}
