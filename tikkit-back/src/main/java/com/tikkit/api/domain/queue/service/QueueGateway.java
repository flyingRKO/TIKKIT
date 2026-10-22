package com.tikkit.api.domain.queue.service;

import com.tikkit.api.domain.queue.dto.QueueStatusResponse;

/**
 * 대기열의 모든 동작 (Task 031). 구현을 고르는 자리는 {@code QueueStrategyConfig} 한 곳이다.
 *
 * <h2>왜 인터페이스인가</h2>
 * {@code tikkit.queue.enabled}가 기본 false이고, 그때는 Redis 연결 팩토리가 아예 없다.
 * 그런데 {@code ReservationService}와 인터셉터는 <b>항상</b> 가드를 호출해야 한다 — 대기열이
 * 꺼졌다고 호출부가 사라지면, 켜고 끌 때마다 호출 코드를 넣고 빼야 한다.
 * <p>
 * 그래서 꺼진 상태를 "빈이 없음"이 아니라 <b>"항상 통과시키는 구현"</b>으로 표현한다.
 * {@code tikkit.cache.type=none}이 {@code NoOpCacheManager}를 올려서 {@code @Cacheable}을
 * 지우지 않고도 캐시를 끄는 것과 같은 구조다.
 *
 * <h2>왜 빈마다 {@code @ConditionalOnProperty}를 달지 않았나</h2>
 * {@code AuthStrategyConfig} Javadoc이 적어둔 이유 그대로다 — 빈마다 조건을 흩으면
 * <b>조합이 어긋난 상태로도 기동이 된다</b>. 대기열은 "ZSET 접근 + 플래그 캐시 + 입장 스케줄러 +
 * 지표"가 한 세트라서, 하나만 조건이 틀려도 "대기열이 조용히 동작하지 않는" 상태가 된다.
 * 한 곳에서 묶어 만들면 그런 조합이 존재할 수 없다.
 */
public interface QueueGateway {

    /**
     * 이 회차가 대기열을 쓰는가.
     * <p>
     * 인터셉터가 <b>인증을 요구할지 결정하려고</b> 쓴다. 좌석 조회는 {@code permitAll}이라
     * (Task 022의 의도적 결정) 대기열을 켠 회차에서만 로그인을 요구해야 하는데, 그 판단은
     * "입장권이 있나"보다 먼저 와야 한다 — 꺼진 회차에서 비로그인을 401로 끊으면 공개 조회가
     * 깨지고, 켜진 회차에서 통과시키면 {@code memberId} 없이 줄을 세울 수 없다.
     */
    boolean isQueueEnabled(long scheduleId);

    /**
     * 대기열에 진입한다. <b>멱등하다</b> — 이미 줄 서 있으면 순번이 유지되고, 이미 입장했으면
     * {@code ADMITTED}를 돌려준다.
     */
    QueueStatusResponse enter(long scheduleId, long memberId);

    /** 현재 상태. 큐에 없는 회원도 오류가 아니라 {@code EXPIRED}로 답한다. */
    QueueStatusResponse status(long scheduleId, long memberId);

    /** 대기열에서 빠진다. 없는 항목을 지워도 조용히 통과한다(멱등). */
    void leave(long scheduleId, long memberId);

    /**
     * 입장권을 검증하고, 유효하면 만료 시각을 연장한다.
     * <p>
     * 연장을 폴링이 아니라 <b>보호된 API 호출</b>에 붙이는 이유: 폴링하는 사람은 대기 중인
     * 사람이고, 입장한 사람은 좌석 페이지로 떠나 폴링을 멈춘다. 하트비트를 상태 조회에 달면
     * 정작 입장자에게는 걸리지 않는다. 활동하면 유지되고 가만히 있으면 만료되는 게 맞다.
     *
     * @throws com.tikkit.api.common.exception.BusinessException 입장권이 없으면
     *         {@code QUEUE_PASS_REQUIRED}, 만료됐으면 {@code QUEUE_PASS_EXPIRED}
     */
    void requireAdmitted(long scheduleId, long memberId);
}
