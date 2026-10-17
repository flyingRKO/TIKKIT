package com.tikkit.api.domain.performance.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.service.ScheduleService;
import com.tikkit.api.domain.performance.service.SeatmapResponseCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Schedule", description = "회차별 등급·잔여 수량·좌석 조회")
@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final SeatmapResponseCache seatmapResponseCache;

    @Operation(summary = "회차별 등급·가격·잔여 수량 조회")
    @GetMapping("/{id}/ticket-grades")
    public ApiResponse<List<TicketGradeResponse>> ticketGrades(@PathVariable Long id) {
        return ApiResponse.success(scheduleService.getTicketGrades(id));
    }

    /**
     * 좌석 배치도 조회.
     *
     * <p>평소에는 {@code ApiResponse}를 그대로 반환하고 Jackson이 직렬화한다. <b>응답 바이트 캐시가
     * 켜져 있을 때만</b>({@code tikkit.cache.seatmap-response-ttl > 0}) 미리 직렬화해둔 바이트를
     * 그대로 내려준다 — 직렬화 비용이 응답 시간에서 얼마를 차지하는지 재기 위한 실험 경로이고
     * 기본값은 꺼져 있다. 이유는 {@code SeatmapResponseCache} Javadoc 참조.
     *
     * <p>두 경로가 내려주는 바이트는 동일하다. 그래서 반환 타입이 갈려도 FE 계약은 같다.
     */
    @Operation(summary = "회차 좌석 배치도 조회")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ScheduleSeatResponse.class)))
    @GetMapping("/{id}/seats")
    public ResponseEntity<?> seats(@PathVariable Long id) {
        if (!seatmapResponseCache.enabled()) {
            return ResponseEntity.ok(ApiResponse.success(scheduleService.getSeats(id)));
        }

        byte[] cached = seatmapResponseCache.find(id);
        byte[] body = cached != null
                ? cached
                : seatmapResponseCache.store(id, scheduleService.getSeats(id));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
