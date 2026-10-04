package com.tikkit.api.domain.reservation.strategy;

import com.tikkit.api.domain.performance.entity.TicketGrade;
import org.springframework.stereotype.Component;

/**
 * 동시성을 보장하지 않는 기존 방식 (Task 012~018의 동작 그대로). 비교의 기준선이다.
 * <p>
 * 읽은 값을 메모리에서 빼고 더티체킹으로 반영하므로, 동시 요청이 같은 값을 읽으면 나중 커밋이
 * 먼저 커밋을 덮어쓰는 lost update가 난다
 * (재현 결과: {@code docs/improvements/001-overselling-reproduction.md}).
 */
@Component
public class NoLockStrategy implements SeatHoldStrategy {

    public static final String NAME = "미보장";

    @Override
    public void hold(TicketGrade grade, int quantity) {
        grade.decreaseRemaining(quantity);
    }

    @Override
    public String name() {
        return NAME;
    }
}
