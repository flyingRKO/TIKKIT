package com.tikkit.api.domain.queue.dto;

import java.time.Instant;

/**
 * 대기열 상태 응답 (Task 031). <b>진입·폴링·이탈이 모두 이 스키마를 쓴다.</b>
 *
 * <p>스키마를 하나로 묶은 이유: Task 032의 화면이 파서를 하나만 두면 되고, 진입 응답으로 바로
 * 라우팅을 결정할 수 있다(DISABLED면 좌석으로, ADMITTED면 좌석으로, WAITING이면 대기 화면).
 *
 * @param scheduleId           회차 id
 * @param state                상태. 프론트 분기의 기준이다
 * @param position             내 순번 (1-based). 대기 중이 아니면 {@code null}
 * @param totalWaiting         전체 대기 인원. 진행바의 분모로 쓸 수 있다
 * @param estimatedWaitSeconds 예상 대기 시간(초). {@code rank / 초당 입장 인원}이다.
 *                             <b>공칭 속도 기준이라 실제보다 낙관적이다</b> — 스케줄러가
 *                             {@code fixedDelay}라 실제 주기가 설정값보다 길다.
 *                             대기 중이 아니면 0
 * @param pollAfterSeconds     <b>다음 폴링까지 기다릴 초.</b> 백엔드가 간격을 지시한다 —
 *                             10만 명이 2초 고정으로 폴링하면 상태 API 하나에 50,000 RPS가
 *                             몰려서, 좌석 API를 막아 번 여유를 상태 API가 전부 먹는다.
 *                             순번이 뒤쪽일수록 길어지고, 입장·만료 시 0(폴링 중단)이다.
 *                             값을 계약에 넣어두면 프론트를 고치지 않고도 Task 033이 조절할 수 있다
 * @param passExpiresAt        입장권 만료 시각. {@code ADMITTED}가 아니면 {@code null}
 */
public record QueueStatusResponse(
        Long scheduleId,
        QueueState state,
        Long position,
        long totalWaiting,
        long estimatedWaitSeconds,
        long pollAfterSeconds,
        Instant passExpiresAt
) {

    /** 대기열을 쓰지 않는 회차. 프론트는 좌석 페이지로 바로 보낸다. */
    public static QueueStatusResponse disabled(long scheduleId) {
        return new QueueStatusResponse(scheduleId, QueueState.DISABLED, null, 0, 0, 0, null);
    }

    /** 입장 완료. 폴링을 멈춰야 하므로 {@code pollAfterSeconds}가 0이다. */
    public static QueueStatusResponse admitted(long scheduleId, long totalWaiting, Instant passExpiresAt) {
        return new QueueStatusResponse(scheduleId, QueueState.ADMITTED, null, totalWaiting, 0, 0, passExpiresAt);
    }

    /** 큐에 없거나 입장권이 만료됐다. 재진입은 꼬리로 간다. */
    public static QueueStatusResponse expired(long scheduleId, long totalWaiting) {
        return new QueueStatusResponse(scheduleId, QueueState.EXPIRED, null, totalWaiting, 0, 0, null);
    }

    /** 대기 중. {@code rank}는 0-based이고 {@code position}으로는 1을 더해 내려간다. */
    public static QueueStatusResponse waiting(long scheduleId, long rank, long totalWaiting,
                                              long estimatedWaitSeconds, long pollAfterSeconds) {
        return new QueueStatusResponse(scheduleId, QueueState.WAITING, rank + 1, totalWaiting,
                estimatedWaitSeconds, pollAfterSeconds, null);
    }
}
