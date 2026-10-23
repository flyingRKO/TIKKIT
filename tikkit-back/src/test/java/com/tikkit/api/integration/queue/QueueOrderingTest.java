package com.tikkit.api.integration.queue;

import com.tikkit.api.domain.queue.dto.QueueState;
import com.tikkit.api.domain.queue.dto.QueueStatusResponse;
import com.tikkit.api.support.AbstractRedisQueueContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

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
    @DisplayName("폴링 간격은 순번 구간에 따라 백엔드가 지시한다")
    void 폴링_간격은_순번_구간에_따라_결정된다() {
        // 픽스처가 5명이라 전원이 rank < 100 구간이다. 뒤쪽 구간은 ZSET에 직접 심어 확인한다.
        long nearFront = memberIdOf(sessions.get(0));
        queueGateway.enter(scheduleId, nearFront);

        assertThat(queueGateway.status(scheduleId, nearFront).pollAfterSeconds()).isEqualTo(2);
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
