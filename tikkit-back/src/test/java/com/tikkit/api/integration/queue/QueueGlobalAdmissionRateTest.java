package com.tikkit.api.integration.queue;

import com.tikkit.api.support.AbstractRedisQueueContainerTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입장 속도가 켠 회차 수와 무관하다 (Task 037).
 *
 * <h2>무엇을 재현하는가</h2>
 * 전에는 {@code admit-batch-size}가 <b>회차당</b>이었다 — 스케줄러가 켠 회차를 전부 순회하며
 * 회차마다 독립적으로 그만큼 꺼냈으므로 <b>초당 입장 인원 = batch × 켠 회차 수</b>였다.
 * 기본값 50은 회차 1개를 전제한 값이라 회차 2개를 켜는 것만으로 100명/s가 되어 010이 실측한
 * 상한 84명/s를 넘었다. 대기열의 목적이 좌석 API를 지키는 것인데, 운영자가 회차를 하나 더
 * 켜는 것만으로 그 보호가 조용히 뚫린다.
 * <p>
 * 이 클래스의 첫 테스트가 그 재현이다 — 수정 전이면 {@code ADMIT_RATE_PER_SECOND × 2}가
 * 들어가서 실패한다. 부하가 필요한 성질이 아니라 {@code ZCARD}로 바로 보인다.
 *
 * <h2>왜 별도 클래스인가</h2>
 * {@code QueueAdmissionTest}의 픽스처가 회차 1개이고, 이 Task가 보는 것은 <b>회차가 여럿일 때</b>의
 * 동작이다. 같은 베이스를 상속하므로 Spring 컨텍스트는 재사용된다.
 */
class QueueGlobalAdmissionRateTest extends AbstractRedisQueueContainerTest {

    @Autowired
    private MeterRegistry meterRegistry;

    /** id가 작은 쪽. 스케줄러가 id 오름차순으로 순회하므로 예산을 먼저 먹는다. */
    private long firstScheduleId;
    private long secondScheduleId;
    private List<MockHttpSession> firstQueueSessions;
    private List<MockHttpSession> secondQueueSessions;

    @BeforeEach
    void setUpTwoQueues() {
        GradeWithSeats first = createOnSaleGradeWithSeats(10);
        GradeWithSeats second = createOnSaleGradeWithSeats(10);
        firstScheduleId = first.scheduleId();
        secondScheduleId = second.scheduleId();

        // 회차를 두 번 만들면 id가 증가하므로 순회 순서가 곧 생성 순서다.
        assertThat(firstScheduleId).isLessThan(secondScheduleId);

        firstQueueSessions = createMembersWithSession(5);
        secondQueueSessions = createMembersWithSession(5);

        deleteQueue(firstScheduleId);
        deleteQueue(secondScheduleId);
        enableQueue(firstScheduleId);
        enableQueue(secondScheduleId);
    }

    @Test
    @DisplayName("회차를 둘 켜도 한 틱의 총 입장 인원이 전역 예산을 넘지 않는다 — 수정 전에는 예산 × 2가 들어갔다")
    void 켠_회차_수와_무관하게_전역_예산만_입장한다() {
        enqueueAll(firstScheduleId, firstQueueSessions);
        enqueueAll(secondScheduleId, secondQueueSessions);

        queueAdmissionScheduler.run();

        long totalAdmitted = queueRepository.activeSize(firstScheduleId)
                + queueRepository.activeSize(secondScheduleId);
        assertThat(totalAdmitted).isEqualTo(ADMIT_RATE_PER_SECOND);

        // 예산이 앞 회차에서 소진되므로 뒤 회차는 한 명도 들어가지 않는다. 공평하지는 않지만
        // **예측 가능한** 편향이다(QueueFlagRegistry가 id 오름차순을 고정하는 이유).
        assertThat(queueRepository.activeSize(firstScheduleId)).isEqualTo(ADMIT_RATE_PER_SECOND);
        assertThat(queueRepository.activeSize(secondScheduleId)).isZero();

        // 들어가지 못한 사람은 사라지지 않고 줄에 남는다.
        assertThat(queueRepository.waitingSize(firstScheduleId)).isEqualTo(5 - ADMIT_RATE_PER_SECOND);
        assertThat(queueRepository.waitingSize(secondScheduleId)).isEqualTo(5);
    }

    @Test
    @DisplayName("앞 회차가 다 쓰지 않은 예산을 뒤 회차가 이어받는다 — 빈 회차가 슬롯을 낭비하지 않는다")
    void 남은_예산을_다음_회차가_이어받는다() {
        // 앞 회차에 1명만 세운다. 균등 분배였다면 뒤 회차 몫이 예산의 절반으로 묶이고,
        // 앞 회차가 안 쓴 나머지는 그냥 버려진다.
        queueGateway.enter(firstScheduleId, memberIdOf(firstQueueSessions.get(0)));
        enqueueAll(secondScheduleId, secondQueueSessions);

        queueAdmissionScheduler.run();

        assertThat(queueRepository.activeSize(firstScheduleId)).isEqualTo(1);
        assertThat(queueRepository.activeSize(secondScheduleId)).isEqualTo(ADMIT_RATE_PER_SECOND - 1);

        long totalAdmitted = queueRepository.activeSize(firstScheduleId)
                + queueRepository.activeSize(secondScheduleId);
        assertThat(totalAdmitted).isEqualTo(ADMIT_RATE_PER_SECOND);
    }

    @Test
    @DisplayName("예산이 소진돼도 만료된 입장권 수거는 전 회차에서 돈다 — 루프를 분리한 이유다")
    void 예산_소진과_무관하게_만료_입장권을_수거한다() {
        // 앞 회차가 예산을 전부 먹게 만든다.
        enqueueAll(firstScheduleId, firstQueueSessions);

        // 뒤 회차에는 이미 만료된 입장권을 심는다. score가 만료 시각이라 과거 값으로 재현된다.
        long expiredMemberId = memberIdOf(secondQueueSessions.get(0));
        queueRepository.admit(secondScheduleId, List.of(expiredMemberId),
                Instant.now().minusSeconds(60));
        // 그리고 줄 선 사람도 하나 둔다 — 입장은 막히고 수거만 돌아야 한다.
        queueGateway.enter(secondScheduleId, memberIdOf(secondQueueSessions.get(1)));
        assertThat(queueRepository.activeSize(secondScheduleId)).isEqualTo(1);

        queueAdmissionScheduler.run();

        // 입장은 예산 소진으로 막혔는데(활성 0) 만료 엔트리는 사라졌다.
        // 수거를 입장 루프에 묶어 두고 break를 걸면 이 엔트리가 영구히 남고 activeSize가 틀린다.
        assertThat(queueRepository.activeSize(secondScheduleId)).isZero();
        assertThat(queueRepository.activeSize(firstScheduleId)).isEqualTo(ADMIT_RATE_PER_SECOND);
        assertThat(queueRepository.waitingSize(secondScheduleId)).isEqualTo(1);
    }

    @Test
    @DisplayName("대기 인원 게이지는 예산이 소진된 회차의 줄도 센다")
    void 게이지가_예산_소진_회차의_대기도_센다() {
        enqueueAll(firstScheduleId, firstQueueSessions);
        enqueueAll(secondScheduleId, secondQueueSessions);

        queueAdmissionScheduler.run();

        // 집계 루프가 입장 루프와 같이 break되면 뒤 회차의 5명이 빠져서, 대시보드에서는
        // "대기열이 거의 비었다"로 보인다. 측정 군을 바꾼 직후에 그 오독이 일어난다.
        assertThat(waitingSizeGauge()).isEqualTo(10 - ADMIT_RATE_PER_SECOND);
    }

    private void enqueueAll(long scheduleId, List<MockHttpSession> sessions) {
        sessions.forEach(session -> queueGateway.enter(scheduleId, memberIdOf(session)));
    }

    private long waitingSizeGauge() {
        return (long) meterRegistry.get("tikkit.queue.waiting.size").gauge().value();
    }
}
