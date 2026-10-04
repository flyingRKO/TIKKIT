package com.tikkit.api.domain.reservation.strategy;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 조건부 UPDATE — 현재 재고를 WHERE 절에 넣어 DB가 원자적으로 검증·차감하게 한다.
 * <p>
 * 읽은 값을 애플리케이션이 다시 쓰지 않으므로 lost update가 구조적으로 불가능하다.
 * 락을 잡지 않아 대기가 없고, 충돌을 감지해 되돌릴 일도 없으니 재시도도 필요 없다.
 */
@Component
@RequiredArgsConstructor
public class ConditionalUpdateStrategy implements SeatHoldStrategy {

    public static final String NAME = "조건부 UPDATE";

    private final TicketGradeRepository ticketGradeRepository;

    /**
     * 엔티티를 전혀 건드리지 않는 것이 중요하다 — 영속 인스턴스가 더티가 되면 flush 시점에
     * Hibernate가 메모리의 낡은 값으로 UPDATE를 또 발행해서 이 조건부 UPDATE를 덮어쓴다.
     * 그래서 재고 검증도 메모리 값으로 하지 않고 영향받은 행 수(0이면 부족)로 판단한다.
     */
    @Override
    public void hold(TicketGrade grade, int quantity) {
        int updated = ticketGradeRepository.decreaseRemainingQuantity(grade.getId(), quantity, Instant.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
    }

    @Override
    public String name() {
        return NAME;
    }
}
