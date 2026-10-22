package com.tikkit.api.domain.queue.service;

import com.tikkit.api.domain.queue.dto.QueueStatusResponse;

/**
 * 대기열이 꺼진 환경의 구현 ({@code tikkit.queue.enabled=false}, 기본값).
 *
 * <p>Redis를 건드리지 않고, 보호도 하지 않는다. {@code NoOpCacheManager}와 같은 자리다 —
 * 호출부를 지우지 않고도 기능 전체를 끄기 위해 존재한다.
 *
 * <p>상태를 {@code DISABLED}로 답하므로 프론트는 "대기열이 있는 회차인지" 먼저 물을 필요가 없다.
 * 켜진 환경에서 {@code queue_enabled=false}인 회차도 같은 응답을 받는다 — 프론트 입장에서 두
 * 경우를 구분할 이유가 없다.
 */
public class DisabledQueueGateway implements QueueGateway {

    @Override
    public boolean isQueueEnabled(long scheduleId) {
        return false;
    }

    @Override
    public QueueStatusResponse enter(long scheduleId, long memberId) {
        return QueueStatusResponse.disabled(scheduleId);
    }

    @Override
    public QueueStatusResponse status(long scheduleId, long memberId) {
        return QueueStatusResponse.disabled(scheduleId);
    }

    @Override
    public void leave(long scheduleId, long memberId) {
        // 큐가 없으므로 할 일이 없다.
    }

    @Override
    public void requireAdmitted(long scheduleId, long memberId) {
        // 대기열이 꺼져 있으면 전부 통과시킨다. 이게 "플래그를 켜지 않은 회차는 예전과
        // 똑같이 동작한다"를 보장하는 지점이고, 기존 통합 테스트가 그대로 도는 이유다.
    }
}
