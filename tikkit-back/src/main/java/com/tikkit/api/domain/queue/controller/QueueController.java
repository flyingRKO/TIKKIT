package com.tikkit.api.domain.queue.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.queue.dto.QueueStatusResponse;
import com.tikkit.api.domain.queue.service.QueueGateway;
import com.tikkit.api.security.MemberDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기열 API (Task 031). Task 032의 {@code /queue/[scheduleId]} 화면이 이 세 개를 쓴다.
 *
 * <h2>경로를 {@code /queue/schedules/{id}}로 둔 이유</h2>
 * {@code /schedules/{id}/queue}로 하면 {@code ScheduleController}와 경로 소유권이 섞인다 —
 * 좌석 조회에 걸어둔 인터셉터 패턴({@code /api/v1/schedules/*}/seats})과도 접두사가 겹쳐서,
 * 나중에 패턴을 넓히면 대기열 API 자체가 입장권을 요구하는 사고가 난다(줄을 서려면 입장권이
 * 필요한 상태가 된다).
 *
 * <h2>대기열이 꺼진 환경에서도 이 컨트롤러는 올라간다</h2>
 * {@code DisabledQueueGateway}가 {@code DISABLED}를 돌려주므로 프론트는 환경을 몰라도 된다.
 * 404를 주면 프론트가 "대기열이 없는 배포"를 따로 처리해야 한다.
 */
@Tag(name = "Queue", description = "대기열 진입·상태·이탈")
@RestController
@RequestMapping("/api/v1/queue/schedules/{scheduleId}")
@RequiredArgsConstructor
public class QueueController {

    private final QueueGateway queueGateway;

    /**
     * 대기열 진입. <b>멱등이라 200을 쓴다</b> — 재호출이 기본 동작인데 201을 주면 "새로 만들었다"는
     * 거짓말이 된다. 이미 줄 서 있으면 순번이 유지되고, 이미 입장했으면 {@code ADMITTED}가 온다.
     */
    @Operation(summary = "대기열 진입 (멱등)")
    @PostMapping("/entries")
    public ApiResponse<QueueStatusResponse> enter(@AuthenticationPrincipal MemberDetails memberDetails,
                                                  @PathVariable Long scheduleId) {
        return ApiResponse.success(queueGateway.enter(scheduleId, memberDetails.getMemberId()));
    }

    /**
     * 상태 폴링. 응답 스키마가 진입과 같아서 프론트 파서가 하나다.
     * <p>
     * 큐에 없는 회원도 200 + {@code EXPIRED}로 답한다 — "줄 서 있지 않다"는 오류가 아니다.
     * 다음 폴링 간격은 응답의 {@code pollAfterSeconds}가 지시한다.
     */
    @Operation(summary = "대기열 상태 조회")
    @GetMapping("/status")
    public ApiResponse<QueueStatusResponse> status(@AuthenticationPrincipal MemberDetails memberDetails,
                                                   @PathVariable Long scheduleId) {
        return ApiResponse.success(queueGateway.status(scheduleId, memberDetails.getMemberId()));
    }

    /**
     * 대기열 이탈. 없는 항목을 지워도 200이다(멱등).
     * <p>
     * 프론트는 이탈 경고에서 확인을 눌렀을 때 호출한다. {@code sendBeacon}으로 보내면 응답을
     * 못 받으므로 best-effort이고, 실패해도 브라우저를 닫은 사람과 같은 상태가 된다 —
     * 유령으로 남아 예상 대기시간이 길게 표시될 뿐이다.
     */
    @Operation(summary = "대기열 이탈")
    @DeleteMapping("/entries")
    public ApiResponse<Void> leave(@AuthenticationPrincipal MemberDetails memberDetails,
                                   @PathVariable Long scheduleId) {
        queueGateway.leave(scheduleId, memberDetails.getMemberId());
        return ApiResponse.success();
    }
}
