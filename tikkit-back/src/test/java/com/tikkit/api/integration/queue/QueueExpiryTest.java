package com.tikkit.api.integration.queue;

import com.tikkit.api.domain.queue.dto.QueueState;
import com.tikkit.api.support.AbstractRedisQueueContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입장권 만료와 연장 (Task 031).
 *
 * <h2>시계를 다루는 방법</h2>
 * {@code sleep}도, {@code Clock} 빈도, 프로퍼티 변경도 쓰지 않는다. 만료 시각이 ZSET <b>score</b>라서
 * <b>과거 시각을 직접 심을 수 있다</b> — {@code repository.admit(..., 과거)} 한 줄로 "이미 만료된
 * 입장권"이 만들어진다. 결정적이고, {@code pass-ttl}을 줄이려고 프로퍼티를 바꿰 테스트 컨텍스트를
 * 하나 더 만들 필요도 없다(컨텍스트가 늘면 Hikari 풀이 같이 늘어난다).
 * <p>
 * 활성 쪽을 SET + 토큰 키로 만들었으면 이 방법이 불가능했다. 설계의 부수 효과다.
 */
class QueueExpiryTest extends AbstractRedisQueueContainerTest {

    private long scheduleId;
    private long memberId;

    @BeforeEach
    void setUpQueue() {
        GradeWithSeats fixture = createOnSaleGradeWithSeats(10);
        scheduleId = fixture.scheduleId();
        List<MockHttpSession> sessions = createMembersWithSession(1);
        memberId = memberIdOf(sessions.get(0));
        deleteQueue(scheduleId);
        enableQueue(scheduleId);
    }

    @Test
    @DisplayName("만료된 입장권으로 상태를 조회하면 EXPIRED다")
    void 만료된_입장권은_EXPIRED로_보인다() {
        admitWithPassExpiredAt(Instant.now().minusSeconds(1));

        assertThat(queueGateway.status(scheduleId, memberId).state()).isEqualTo(QueueState.EXPIRED);
    }

    @Test
    @DisplayName("만료 시각이 현재와 같으면 만료로 본다 — 수거 기준과 같아야 틈이 생기지 않는다")
    void 경계_시각은_만료로_판정한다() {
        Instant now = Instant.now();
        admitWithPassExpiredAt(now);

        // 조회는 만료로 보고 수거는 남겨두면, 그 사용자는 EXPIRED인데 재진입도 안 되는 상태가 된다.
        assertThat(queueGateway.status(scheduleId, memberId).state()).isEqualTo(QueueState.EXPIRED);
        assertThat(queueRepository.removeExpiredPasses(scheduleId, now)).isEqualTo(1);
    }

    @Test
    @DisplayName("배치가 만료된 입장권을 수거한다")
    void 배치가_만료된_입장권을_수거한다() {
        admitWithPassExpiredAt(Instant.now().minusSeconds(1));
        assertThat(queueRepository.activeSize(scheduleId)).isEqualTo(1);

        queueAdmissionScheduler.run();

        assertThat(queueRepository.activeSize(scheduleId)).isZero();
    }

    @Test
    @DisplayName("보호된 API 호출이 성공하면 만료 시각이 연장된다 — 폴링이 아니라 활동이 하트비트다")
    void 검증_성공이_만료_시각을_연장한다() {
        Instant soon = Instant.now().plusSeconds(5);
        admitWithPassExpiredAt(soon);

        queueGateway.requireAdmitted(scheduleId, memberId);

        Instant extended = queueRepository.passExpiresAt(scheduleId, memberId);
        assertThat(extended).isNotNull();
        // pass-ttl 기본값 15분만큼 밀렸다.
        assertThat(extended).isAfter(soon.plusSeconds(60));
    }

    @Test
    @DisplayName("연장은 더 늦은 시각으로만 간다 — 순서가 뒤바뀐 요청이 만료를 되감지 못한다 (ZADD GT)")
    void 연장은_만료_시각을_되감지_않는다() {
        Instant far = Instant.now().plusSeconds(3600);
        admitWithPassExpiredAt(far);

        boolean changed = queueRepository.extendPass(scheduleId, memberId, Instant.now().plusSeconds(10));

        assertThat(changed).isFalse();
        assertThat(queueRepository.passExpiresAt(scheduleId, memberId)).isEqualTo(far.truncatedTo(
                java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("수거된 회원은 연장으로 되살아나지 않는다 (ZADD XX)")
    void 수거된_회원은_연장으로_되살아나지_않는다() {
        // 활성에 없는 회원을 연장 시도한다. XX가 없으면 여기서 입장권이 생겨버린다.
        boolean changed = queueRepository.extendPass(scheduleId, memberId, Instant.now().plusSeconds(900));

        assertThat(changed).isFalse();
        assertThat(queueRepository.passExpiresAt(scheduleId, memberId)).isNull();
    }

    /** 이미 만료된(또는 특정 시각에 만료되는) 입장권을 만든다. score가 만료 시각이라 가능하다. */
    private void admitWithPassExpiredAt(Instant passExpiresAt) {
        queueRepository.admit(scheduleId, List.of(memberId), passExpiresAt);
    }
}
