package com.tikkit.api.domain.queue.dto;

/**
 * 대기열 상태 (Task 031). Task 032의 화면이 이 값으로 분기한다.
 *
 * <p>네 값이 전부이고, "큐에 없음"과 "입장권 만료"를 하나로 묶은 것이 설계 결정이다 —
 * 프론트가 할 일이 양쪽 다 "다시 줄 서기"라서 구분할 이유가 없다. 둘로 나누면 화면 분기만
 * 늘어난다.
 */
public enum QueueState {

    /**
     * 이 회차는 대기열을 쓰지 않는다. 좌석 페이지로 바로 가면 된다.
     * <p>
     * {@code schedules.queue_enabled}가 false이거나, 애플리케이션 레벨에서 대기열이 꺼져 있을 때
     * ({@code tikkit.queue.enabled=false}) 나온다. 프론트는 두 경우를 구분할 필요가 없다.
     * <p>
     * 이 값이 있어서 <b>프론트가 "대기열이 있는 회차인지" 먼저 묻지 않아도 된다</b> — 항상
     * 진입을 호출하고 응답으로 분기한다.
     */
    DISABLED,

    /** 대기 중. {@code position}과 {@code estimatedWaitSeconds}가 채워진다. */
    WAITING,

    /** 입장 완료. {@code passExpiresAt}까지 좌석 조회·예매 선점이 허용된다. */
    ADMITTED,

    /**
     * 큐에 없거나 입장권이 만료됐다. 재진입하면 <b>꼬리로 간다</b> —
     * 앞자리를 보존해 주면 만료가 아무 의미가 없어진다.
     * <p>
     * 큐 키가 Redis {@code allkeys-lru}로 축출된 경우도 이 상태로 떨어진다.
     */
    EXPIRED
}
