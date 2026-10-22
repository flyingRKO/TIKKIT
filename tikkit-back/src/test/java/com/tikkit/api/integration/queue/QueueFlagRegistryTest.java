package com.tikkit.api.integration.queue;

import com.tikkit.api.support.AbstractRedisQueueContainerTest;
import com.tikkit.api.support.QueryCounter;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code queue_enabled} 스냅샷 캐시 (Task 031).
 *
 * <p>Redis를 쓰지 않는 테스트지만 대기열이 켜진 컨텍스트를 상속한다 — {@code QueueFlagRegistry}
 * 빈 자체가 {@code tikkit.queue.enabled=true}일 때만 올라가기 때문이다. 컨텍스트를 재사용하므로
 * 비용도 들지 않는다.
 *
 * <p>"요청마다 DB를 읽지 않는다"는 주장은 수치로 고정해야 한다. 그래야 나중에 누가 캐시를
 * 떼거나 TTL 로직을 고쳤을 때 조용히 통과하지 않는다. {@link QueryCounter}를 재사용한다
 * (Task 028 하네스).
 */
class QueueFlagRegistryTest extends AbstractRedisQueueContainerTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private QueryCounter queryCounter;
    private long scheduleId;

    @BeforeEach
    void setUpFixture() {
        queryCounter = new QueryCounter(entityManagerFactory);
        scheduleId = createOnSaleGradeWithSeats(1).scheduleId();
    }

    @Test
    @DisplayName("플래그를 켜면 스냅샷에 들어온다")
    void 켠_회차가_스냅샷에_들어온다() {
        assertThat(queueFlagRegistry.enabled(scheduleId)).isFalse();

        enableQueue(scheduleId);

        assertThat(queueFlagRegistry.enabled(scheduleId)).isTrue();
        assertThat(queueFlagRegistry.enabledScheduleIds()).contains(scheduleId);
    }

    @Test
    @DisplayName("TTL 안에서는 반복 조회가 쿼리를 한 번만 보낸다 — 좌석 조회마다 DB로 가지 않는다")
    void TTL_안에서는_쿼리가_한_번만_나간다() {
        enableQueue(scheduleId);

        // invalidate() 직후라 첫 조회가 DB를 읽는다.
        long first = queryCounter.count(() -> queueFlagRegistry.enabled(scheduleId));
        assertThat(first).isEqualTo(1);

        // 이후는 스냅샷에서 답한다. 100번 물어도 0이어야 한다.
        long repeated = queryCounter.count(() -> {
            for (int i = 0; i < 100; i++) {
                queueFlagRegistry.enabled(scheduleId);
            }
        });
        assertThat(repeated).isZero();
    }

    @Test
    @DisplayName("스냅샷을 버리면 다음 조회가 DB를 다시 읽어 변경을 반영한다")
    void 스냅샷을_버리면_변경이_반영된다() {
        enableQueue(scheduleId);
        assertThat(queueFlagRegistry.enabled(scheduleId)).isTrue();

        // 스냅샷을 거치지 않고 DB만 바꾼다 — 캐시가 실제로 값을 들고 있는지 보려는 것이다.
        jdbcTemplate.update("UPDATE schedules SET queue_enabled = false WHERE id = ?", scheduleId);
        assertThat(queueFlagRegistry.enabled(scheduleId))
                .as("TTL이 남아 있으면 옛 값을 그대로 쓴다")
                .isTrue();

        queueFlagRegistry.invalidate();

        assertThat(queueFlagRegistry.enabled(scheduleId)).isFalse();
    }

    @Test
    @DisplayName("켜진 회차가 없으면 스냅샷이 비어 있고, 입장 배치는 아무 일도 하지 않는다")
    void 켜진_회차가_없으면_배치가_돌지_않는다() {
        queueFlagRegistry.invalidate();

        assertThat(queueFlagRegistry.enabledScheduleIds()).isEmpty();
        // 예외 없이 즉시 반환한다 — 평소(대기열을 안 켠 상태)의 동작이다.
        queueAdmissionScheduler.run();
    }
}
