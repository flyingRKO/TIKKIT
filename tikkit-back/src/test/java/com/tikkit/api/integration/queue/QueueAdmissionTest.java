package com.tikkit.api.integration.queue;

import com.tikkit.api.domain.queue.dto.QueueState;
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
 * 입장 배치 (Task 031).
 *
 * <p>스케줄러를 {@code run()}으로 직접 부른다. {@code SchedulingConfig}가 {@code @Profile("!test")}라
 * 테스트 프로필에서는 틱이 돌지 않고({@code CacheEvictionTest} 선례), 그래서 타이밍에 의존하지 않는
 * 결정적 테스트가 된다.
 */
class QueueAdmissionTest extends AbstractRedisQueueContainerTest {

    @Autowired
    private MeterRegistry meterRegistry;

    private long scheduleId;
    private List<MockHttpSession> sessions;

    @BeforeEach
    void setUpQueue() {
        GradeWithSeats fixture = createOnSaleGradeWithSeats(10);
        scheduleId = fixture.scheduleId();
        sessions = createMembersWithSession(7);
        deleteQueue(scheduleId);
        enableQueue(scheduleId);
    }

    /**
     * 이 테스트가 <b>test 프로필에서 ShedLock이 걸리지 않는다는 실증</b>이기도 하다.
     * {@code run()}을 연속 세 번 부르는데 {@code @SchedulerLock(lockAtLeastFor = "900ms")}가
     * 실제로 동작했다면 두 번째 호출이 건너뛰어져 입장 인원이 늘지 않는다.
     * <p>
     * 이유: {@code @EnableSchedulerLock}과 {@code LockProvider}가 {@code @Profile("!test")}인
     * {@code SchedulingConfig}에만 있어서 어드바이저 자체가 컨텍스트에 없고, 어노테이션은 그때
     * 아무 일도 하지 않는다. {@code CacheEvictionTest}에 "락 기록을 안 지우면 배치가 조용히
     * 건너뛰어진다"는 주석이 있었는데 Task 031에서 틀렸음을 확인하고 지웠다.
     */
    @Test
    @DisplayName("한 틱에 정확히 admit-batch-size만큼 입장하고, 남은 인원은 다음 틱으로 넘어간다")
    void 한_틱에_배치_크기만큼_입장한다() {
        sessions.forEach(session -> queueGateway.enter(scheduleId, memberIdOf(session)));
        assertThat(queueRepository.waitingSize(scheduleId)).isEqualTo(7);

        queueAdmissionScheduler.run();
        assertThat(queueRepository.activeSize(scheduleId)).isEqualTo(ADMIT_BATCH_SIZE);
        assertThat(queueRepository.waitingSize(scheduleId)).isEqualTo(7 - ADMIT_BATCH_SIZE);

        queueAdmissionScheduler.run();
        assertThat(queueRepository.activeSize(scheduleId)).isEqualTo(ADMIT_BATCH_SIZE * 2);

        // 남은 인원이 배치 크기보다 적으면 있는 만큼만 입장한다.
        queueAdmissionScheduler.run();
        assertThat(queueRepository.activeSize(scheduleId)).isEqualTo(7);
        assertThat(queueRepository.waitingSize(scheduleId)).isZero();
    }

    @Test
    @DisplayName("빈 큐에서 배치가 돌아도 예외 없이 아무 일도 하지 않는다")
    void 빈_큐에서는_아무_일도_하지_않는다() {
        queueAdmissionScheduler.run();

        assertThat(queueRepository.waitingSize(scheduleId)).isZero();
        assertThat(queueRepository.activeSize(scheduleId)).isZero();
    }

    @Test
    @DisplayName("입장한 회원은 활성에 있고 대기에서 빠진다")
    void 입장한_회원은_활성으로_옮겨진다() {
        long memberId = memberIdOf(sessions.get(0));
        queueGateway.enter(scheduleId, memberId);

        queueAdmissionScheduler.run();

        assertThat(queueRepository.passExpiresAt(scheduleId, memberId)).isNotNull();
        assertThat(queueRepository.waitingRank(scheduleId, memberId)).isNull();
    }

    @Test
    @DisplayName("활성과 대기에 같이 남은 중복은 다음 틱이 스스로 치운다 — ZADD 먼저, ZREM 나중의 결과다")
    void 활성과_대기에_중복으로_남은_회원을_자가_치유한다() {
        long memberId = memberIdOf(sessions.get(0));

        // "활성에 추가"까지만 하고 끊긴 상태를 만든다. 스케줄러가 ZADD → ZREM 순서로 돌기 때문에
        // 그 사이에 프로세스가 죽으면 실제로 이 상태가 된다.
        queueGateway.enter(scheduleId, memberId);
        queueRepository.admit(scheduleId, List.of(memberId), Instant.now().plusSeconds(900));
        queueRepository.enqueue(scheduleId, memberId, Instant.now());

        assertThat(queueRepository.waitingRank(scheduleId, memberId)).isZero();
        assertThat(queueRepository.passExpiresAt(scheduleId, memberId)).isNotNull();

        // 사용자는 이 상태에서도 정상 입장으로 보인다 — 상태 조회가 활성을 먼저 보기 때문이다.
        assertThat(queueGateway.status(scheduleId, memberId).state()).isEqualTo(QueueState.ADMITTED);

        queueAdmissionScheduler.run();

        // 중복이 사라졌다. 후보마다 ZSCORE를 돌려 걸러내지 않아도 ZREM이 치운다.
        assertThat(queueRepository.waitingRank(scheduleId, memberId)).isNull();
        assertThat(queueGateway.status(scheduleId, memberId).state()).isEqualTo(QueueState.ADMITTED);
    }

    @Test
    @DisplayName("입장 인원 카운터가 누적된다")
    void 입장_인원_카운터가_누적된다() {
        double before = admittedCount();
        sessions.forEach(session -> queueGateway.enter(scheduleId, memberIdOf(session)));

        queueAdmissionScheduler.run();

        assertThat(admittedCount() - before).isEqualTo(ADMIT_BATCH_SIZE);
    }

    @Test
    @DisplayName("대기 인원 게이지는 스케줄러가 갱신한다 — Gauge가 스크랩마다 Redis를 왕복하지 않는다")
    void 대기_인원_게이지를_스케줄러가_갱신한다() {
        sessions.forEach(session -> queueGateway.enter(scheduleId, memberIdOf(session)));

        queueAdmissionScheduler.run();

        // 7명 중 ADMIT_BATCH_SIZE명이 입장했으므로 남은 대기 인원이 게이지에 들어간다.
        assertThat(waitingSizeGauge()).isEqualTo(7 - ADMIT_BATCH_SIZE);
    }

    @Test
    @DisplayName("기동 시점에 지표가 0으로 미리 등록돼 있다 — 0건과 계측 없음을 구분할 수 있어야 한다")
    void 지표가_미리_등록돼_있다() {
        assertThat(meterRegistry.find("tikkit.queue.admitted").counter()).isNotNull();
        assertThat(meterRegistry.find("tikkit.queue.waiting.size").gauge()).isNotNull();
        assertThat(meterRegistry.find("tikkit.queue.pass.checks")
                .tag("result", "pass").tag("reason", "OK").counter()).isNotNull();
        assertThat(meterRegistry.find("tikkit.queue.pass.checks")
                .tag("result", "reject").tag("reason", "QUEUE_PASS_REQUIRED").counter()).isNotNull();
        assertThat(meterRegistry.find("tikkit.queue.pass.checks")
                .tag("result", "reject").tag("reason", "QUEUE_PASS_EXPIRED").counter()).isNotNull();
    }

    private double admittedCount() {
        return meterRegistry.get("tikkit.queue.admitted").counter().count();
    }

    private double waitingSizeGauge() {
        return meterRegistry.get("tikkit.queue.waiting.size").gauge().value();
    }
}
