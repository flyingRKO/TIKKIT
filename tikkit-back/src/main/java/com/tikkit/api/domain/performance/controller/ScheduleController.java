package com.tikkit.api.domain.performance.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Schedule", description = "회차별 등급·잔여 수량 조회")
@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @Operation(summary = "회차별 등급·가격·잔여 수량 조회")
    @GetMapping("/{id}/ticket-grades")
    public ApiResponse<List<TicketGradeResponse>> ticketGrades(@PathVariable Long id) {
        return ApiResponse.success(scheduleService.getTicketGrades(id));
    }
}
