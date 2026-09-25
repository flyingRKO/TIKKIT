package com.tikkit.api.domain.performance.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.entity.Grade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "Schedule", description = "회차별 등급·잔여 수량 조회")
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    @Operation(summary = "회차별 등급·가격·잔여 수량 조회")
    @GetMapping("/{id}/ticket-grades")
    public ApiResponse<List<TicketGradeResponse>> ticketGrades(@PathVariable Long id) {
        // TODO(Task 009): 실제 회차의 등급별 잔여 수량 조회 로직 연결
        List<TicketGradeResponse> dummy = List.of(
                new TicketGradeResponse(1L, Grade.VIP, new BigDecimal("150000"), 30),
                new TicketGradeResponse(2L, Grade.R, new BigDecimal("99000"), 80),
                new TicketGradeResponse(3L, Grade.S, new BigDecimal("66000"), 120)
        );
        return ApiResponse.success(dummy);
    }
}
