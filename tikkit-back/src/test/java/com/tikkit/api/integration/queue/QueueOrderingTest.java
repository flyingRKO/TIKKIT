package com.tikkit.api.integration.queue;

import com.tikkit.api.domain.queue.QueueKeys;
import com.tikkit.api.domain.queue.dto.QueueState;
import com.tikkit.api.domain.queue.dto.QueueStatusResponse;
import com.tikkit.api.support.AbstractRedisQueueContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기열 순서 (Task 031).
 *
 * <h2>동시 진입의 상대 순서는 검증하지 않는다</h2>
 * score가 {@code Instant.now().toEpochMilli()}라서 <b>같은 ms에 둘이 들어오면 멤버 문자열
 * 사전순으로 갈린다</b> — {@code "10" < "9"}이므로 회원 id의 자릿수가 섞이면 순서가 뒤집힌다.
 * 그래서 순차로 호출해 ms를 벌리고, 동시 진입은 "둘 다 줄에 있다"까지만 본다.
 * 이건 테스트의 한계가 아니라 <b>설계가 보장하지 않는 범위</b>다.
 */
class QueueOrderingTest extends AbstractRedisQueueContainerTest {

    // 합성 멤버를 한 번의 ZADD로 심기 위해서만 쓴다. 리포지토리를 거치면 왕복이 멤버 수만큼 생긴다.
    @Autowired
    private StringRedisTemplate redis;

    private long scheduleId;
    private List<MockHttpSession> sessions;

    @BeforeEach
    void setUpQueue() {
        GradeWithSeats fixture = createOnSaleGradeWithSeats(10);
        scheduleId = fixture.scheduleId();
        sessions = createMembersWithSession(5);
        deleteQueue(scheduleId);
        enableQueue(scheduleId);
    }

    @Test
    @DisplayName("순차로 진입하면 들어온 순서대로 순번을 받는다")
    void 순차로_진입하면_들어온_순서대로_순번을_받는다() {
        for (int i = 0; i < sessions.size(); i++) {
            QueueStatusResponse response = queueGateway.enter(scheduleId, memberIdOf(sessions.get(i)));

            assertThat(response.state()).isEqualTo(QueueState.WAITING);
            assertThat(response.position()).isEqualTo(i + 1L);
            assertThat(response.totalWaiting()).isEqualTo(i + 1L);
        }
    }

    @Test
    @DisplayName("같은 회원이 다시 진입해도 순번이 뒤로 밀리지 않는다 — ZADD NX가 진입 시각을 보존한다")
    void 같은_회원이_다시_진입해도_순번이_밀리지_않는다() {
        long first = memberIdOf(sessions.get(0));
        queueGateway.enter(scheduleId, first);
        queueGateway.enter(scheduleId, memberIdOf(sessions.get(1)));
        queueGateway.enter(scheduleId, memberIdOf(sessions.get(2)));

        // 더블클릭·폴링 중복·새로고침이 전부 이 경로다. 재진입으로 앞자리를 살 수도 없다.
        QueueStatusResponse reentered = queueGateway.enter(scheduleId, first);

        assertThat(reentered.position()).isEqualTo(1);
        assertThat(reentered.totalWaiting()).isEqualTo(3);
    }

    @Test
    @DisplayName("입장 배치가 한 틱 돌면 앞사람은 ADMITTED가 되고 뒷사람 순번이 당겨진다")
    void 입장_배치_후_뒷사람_순번이_당겨진다() {
        sessions.forEach(session -> queueGateway.enter(scheduleId, memberIdOf(session)));

        queueAdmissionScheduler.run();

        // ADMIT_BATCH_SIZE(3)명이 입장한다.
        for (int i = 0; i < ADMIT_BATCH_SIZE; i++) {
            QueueStatusResponse admitted = queueGateway.status(scheduleId, memberIdOf(sessions.get(i)));
            assertThat(admitted.state()).isEqualTo(QueueState.ADMITTED);
            assertThat(admitted.passExpiresAt()).isNotNull();
            // 입장했으면 폴링을 멈춰야 한다.
            assertThat(admitted.pollAfterSeconds()).isZero();
        }

        QueueStatusResponse stillWaiting = queueGateway.status(scheduleId, memberIdOf(sessions.get(3)));
        assertThat(stillWaiting.state()).isEqualTo(QueueState.WAITING);
        assertThat(stillWaiting.position()).isEqualTo(1);
        assertThat(stillWaiting.totalWaiting()).isEqualTo(sessions.size() - ADMIT_BATCH_SIZE);
    }

    @Test
    @DisplayName("앞사람이 이탈하면 뒷사람 순번이 당겨진다")
    void 이탈하면_뒷사람_순번이_당겨진다() {
        long first = memberIdOf(sessions.get(0));
        long second = memberIdOf(sessions.get(1));
        queueGateway.enter(scheduleId, first);
        queueGateway.enter(scheduleId, second);

        queueGateway.leave(scheduleId, first);

        assertThat(queueGateway.status(scheduleId, second).position()).isEqualTo(1);
        // 이탈한 사람은 큐에 없으므로 EXPIRED다 — "줄 서 있지 않다"는 오류가 아니다.
        assertThat(queueGateway.status(scheduleId, first).state()).isEqualTo(QueueState.EXPIRED);
    }

    @Test
    @DisplayName("예상 대기시간은 rank / 초당 입장 인원이고, 맨 앞이면 0초다")
    void 예상_대기시간은_순번을_입장_속도로_나눈_값이다() {
        sessions.forEach(session -> queueGateway.enter(scheduleId, memberIdOf(session)));

        // admit-batch-size=3, admit-interval=1s(기본) → 초당 3명.
        // rank 0 → 0초, rank 4 → ceil(4/3) = 2초.
        assertThat(queueGateway.status(scheduleId, memberIdOf(sessions.get(0))).estimatedWaitSeconds())
                .isZero();
        assertThat(queueGateway.status(scheduleId, memberIdOf(sessions.get(4))).estimatedWaitSeconds())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("폴링 간격은 순번 구간에 따라 백엔드가 지시한다 — 2/5/10/60초 네 구간")
    void 폴링_간격은_순번_구간에_따라_결정된다() {
        long member = memberIdOf(sessions.get(0));

        // 구간마다 대표값 하나만 보면 경계가 안 잡힌다. 조건이 전부 `<`이고 rank가 0-based라
        // off-by-one 하나로 구간 하나가 통째로 밀리는데, 증상은 "N번째 사람만 폴링이 다르다"뿐이다.
        // 그래서 경계 양쪽을 다 고정한다. 심는 비용은 경계 하나당 ZADD 왕복 1회다.

        // 맨 앞. rank 0도 2초 구간에 든다(`rank < 100`).
        assertThat(pollSecondsWithGhostsAhead(member, 0)).isEqualTo(2);

        // 2초 ↔ 5초 경계. NEAR_FRONT_RANK = 100이므로 rank 99까지가 2초다.
        assertThat(pollSecondsWithGhostsAhead(member, 99)).isEqualTo(2);
        assertThat(pollSecondsWithGhostsAhead(member, 100)).isEqualTo(5);

        // 5초 ↔ 10초 경계. MID_RANK = 1,000.
        assertThat(pollSecondsWithGhostsAhead(member, 999)).isEqualTo(5);
        assertThat(pollSecondsWithGhostsAhead(member, 1_000)).isEqualTo(10);

        // 10초 ↔ 60초 경계. FAR_RANK = 10,000 — Task 033이 추가한 구간이라 회귀 위험이 가장 크다.
        // 이 경계가 틀리면 대기 10만 명의 상태 API 부하가 2,630 RPS에서 10,130 RPS로 뛰는데,
        // 저부하 테스트로는 아무 증상도 보이지 않는다.
        assertThat(pollSecondsWithGhostsAhead(member, 9_999)).isEqualTo(10);
        assertThat(pollSecondsWithGhostsAhead(member, 10_000)).isEqualTo(60);
    }

    /**
     * 실제 회원을 원하는 순번에 놓고 그때의 {@code pollAfterSeconds}를 돌려준다.
     *
     * @param ghostsAhead 앞에 세울 합성 멤버 수. 이 값이 그 회원의 0-based rank가 된다
     */
    private long pollSecondsWithGhostsAhead(long memberId, int ghostsAhead) {
        deleteQueue(scheduleId);
        if (ghostsAhead > 0) {
            seedGhostsAhead(scheduleId, ghostsAhead);
        }
        return queueGateway.enter(scheduleId, memberId).pollAfterSeconds();
    }

    /**
     * 대기 ZSET에 합성 멤버를 심는다.
     *
     * <h2>{@code ZADD} 한 번으로 심는다</h2>
     * {@code queueRepository.enqueue()}를 1만 번 부르면 Redis 왕복이 1만 번이라 테스트 하나가
     * 수십 초로 늘어난다. 부하 테스트의 {@code load-test/redis/seed-waiting.sh}가 Lua로 묶는 것과
     * 같은 이유이고, 같은 트릭을 쓰므로 테스트와 하네스가 같은 모양의 큐를 만든다.
     *
     * <h2>멤버 id를 1,000만부터 쓴다</h2>
     * 실제 회원 id와 겹치면 {@code ZADD}가 그 회원의 score를 덮어 순번이 바뀐다. 그러면
     * "앞에 N명을 세웠다"는 전제가 조용히 깨진다.
     *
     * <h2>score를 과거로 준다</h2>
     * 합성 멤버가 실제 회원보다 <b>앞에</b> 서야 순번이 밀린다. 미래로 주면 뒤에 서고
     * rank가 0이 되어, 어느 구간을 재든 전부 2초가 나온다.
     */
    private void seedGhostsAhead(long scheduleId, int count) {
        long base = Instant.now().toEpochMilli() - 600_000L;
        Set<ZSetOperations.TypedTuple<String>> ghosts = new HashSet<>();
        for (int i = 1; i <= count; i++) {
            ghosts.add(ZSetOperations.TypedTuple.of(String.valueOf(10_000_000L + i), (double) (base + i)));
        }
        redis.opsForZSet().add(QueueKeys.waiting(scheduleId), ghosts);
    }

    @Test
    @DisplayName("대기열이 꺼진 회차는 진입을 호출해도 DISABLED를 돌려준다 — 프론트가 분기 하나로 끝난다")
    void 꺼진_회차는_DISABLED를_돌려준다() {
        disableQueue(scheduleId);

        QueueStatusResponse response = queueGateway.enter(scheduleId, memberIdOf(sessions.get(0)));

        assertThat(response.state()).isEqualTo(QueueState.DISABLED);
        assertThat(response.position()).isNull();
        // Redis를 건드리지 않았다는 증거.
        assertThat(queueRepository.waitingSize(scheduleId)).isZero();
    }
}
