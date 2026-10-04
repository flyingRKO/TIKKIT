package com.tikkit.api.domain.reservation.strategy;

import org.springframework.stereotype.Component;

/**
 * 현재 적용 중인 재고 차감 방식을 들고 있는 홀더. Task 019 비교 실험용이며 정리 커밋에서 삭제한다.
 * <p>
 * {@code @Qualifier}로 주입하면 런타임에 바꿀 수 없고, {@code Map<String, SeatHoldStrategy>}만 주입받으면
 * "지금 쓰는 방식"이라는 개념이 없다. 비교 테스트가 같은 스프링 컨텍스트에서 네 방식을 번갈아 돌려야 해서
 * 가변 홀더를 둔다.
 * <p>
 * 기본값은 {@link NoLockStrategy} — 이 커밋에서 운영 동작을 바꾸지 않기 위함이다. 조건부 UPDATE를
 * 채택하는 커밋에서 기본값을 바꾼다.
 */
@Component
public class SeatHoldStrategyHolder {

    private final SeatHoldStrategy defaultStrategy;
    private volatile SeatHoldStrategy current;

    public SeatHoldStrategyHolder(NoLockStrategy defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
        this.current = defaultStrategy;
    }

    public SeatHoldStrategy current() {
        return current;
    }

    public void setStrategy(SeatHoldStrategy strategy) {
        this.current = strategy;
    }

    /** 비교 테스트가 끝나고 원래 방식으로 되돌릴 때 쓴다 (컨텍스트가 캐시되어 테스트 간에 공유된다). */
    public void reset() {
        this.current = defaultStrategy;
    }
}
