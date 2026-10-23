package com.tikkit.api.domain.queue.repository;

import com.tikkit.api.domain.queue.QueueKeys;
import com.tikkit.api.domain.queue.config.QueueProperties;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.connection.zset.DefaultTuple;
import org.springframework.data.redis.connection.zset.Tuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 대기열 ZSET 접근 (Task 031). Redis 명령을 이 클래스 밖으로 흘리지 않는다.
 *
 * <p>키 구조와 "활성 쪽을 ZSET으로 둔 이유"는 {@link QueueKeys} Javadoc에 있다.
 *
 * <h2>고수준 API를 기본으로 쓰고 두 곳만 내려간다</h2>
 * {@code opsForZSet()}에 필요한 연산이 거의 다 있다. 두 가지만 {@link RedisCallback}으로
 * 내려가는데 이유가 다르다.
 * <ul>
 *   <li>{@link #extendPass}: {@code ZSetOperations}는 {@code NX}({@code addIfAbsent})만 노출하고
 *       <b>{@code GT}/{@code XX}를 노출하지 않는다.</b> {@code ZAddArgs}는 저수준
 *       {@code RedisZSetCommands}에만 있다</li>
 *   <li>{@link #admit}: 파이프라인 안에서는 템플릿의 타입 세이프 API를 쓸 수 없다
 *       ({@code SessionCallback}이 받는 {@code RedisOperations}가 제네릭 메서드라 String 키를
 *       넣으려면 캐스팅이 들어간다). 저수준 커넥션이 오히려 깔끔하다</li>
 * </ul>
 *
 * <h2>{@code @Repository}를 붙이지 않는다</h2>
 * 붙이면 컴포넌트 스캔이 <b>조건을 무시하고</b> 등록을 시도한다. 대기열이 꺼진 환경에는
 * {@code StringRedisTemplate} 빈이 없어서 기동이 깨진다 — 실제로 그렇게 짰다가 테스트 176개가
 * 한꺼번에 빨개졌고, 증상이 "대기열과 무관한 리포지토리 테스트 전부 실패"로 나타났다.
 * {@code QueueStrategyConfig}의 중첩 설정이 {@code @Bean}으로 만든다.
 */
public class WaitingQueueRedisRepository {

    private final StringRedisTemplate redis;
    private final QueueProperties properties;

    public WaitingQueueRedisRepository(StringRedisTemplate redis, QueueProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 대기열에 넣는다. <b>이미 줄 서 있으면 진입 시각을 건드리지 않는다</b>({@code ZADD NX}).
     * <p>
     * 이 한 가지 옵션이 진입 경로의 동시성을 전부 닫는다 — 더블클릭, 폴링 중복, 새로고침이
     * 자기 순번을 뒤로 밀지 않고, 재진입으로 앞자리를 살 수도 없다. 요청 경로는 ShedLock이
     * 없어서(모든 인스턴스에서 동시에 돈다) 이 멱등성이 Lua 없이 성립해야 한다.
     *
     * @return 새로 줄을 섰으면 {@code true}, 이미 있었으면 {@code false}
     */
    public boolean enqueue(long scheduleId, long memberId, Instant now) {
        String key = QueueKeys.waiting(scheduleId);
        Boolean added = redis.opsForZSet().addIfAbsent(key, member(memberId), now.toEpochMilli());
        redis.expire(key, properties.keyTtl());
        return Boolean.TRUE.equals(added);
    }

    /**
     * 앞에 몇 명이 있는지 (0-based). 줄 서 있지 않으면 {@code null}.
     * <p>
     * {@code ZRANK}는 O(log N)이라 100만 명에서도 마이크로초 단위다 — 대기열의 병목은 순번 계산이
     * 아니라 폴링 횟수다. 그래서 응답이 {@code pollAfterSeconds}로 간격을 지시한다.
     */
    public Long waitingRank(long scheduleId, long memberId) {
        return redis.opsForZSet().rank(QueueKeys.waiting(scheduleId), member(memberId));
    }

    /** 대기 인원. */
    public long waitingSize(long scheduleId) {
        return orZero(redis.opsForZSet().size(QueueKeys.waiting(scheduleId)));
    }

    /** 입장 인원. 만료됐지만 아직 수거되지 않은 사람도 포함된다. */
    public long activeSize(long scheduleId) {
        return orZero(redis.opsForZSet().size(QueueKeys.active(scheduleId)));
    }

    /**
     * 입장권 만료 시각. 입장 기록이 없으면 {@code null}.
     * <p>
     * 만료됐는지는 판단하지 않는다 — "언제까지인지"만 돌려주고 비교는 서비스가 한다.
     * 레포지토리가 시계를 들면 테스트에서 시간을 다루기 어려워진다.
     */
    public Instant passExpiresAt(long scheduleId, long memberId) {
        Double score = redis.opsForZSet().score(QueueKeys.active(scheduleId), member(memberId));
        return score == null ? null : Instant.ofEpochMilli(score.longValue());
    }

    /**
     * 대기열 선두 {@code count}명.
     * <p>
     * 반환 순서는 보장하지 않는다. {@code ZRANGE}가 순서대로 주고 커넥터가 {@code LinkedHashSet}에
     * 담기는 하지만 {@code Set}의 계약은 아니다. 입장에서는 "어느 N명인가"만 중요하고
     * (전원이 같은 만료 시각을 받는다) 그 안의 순서는 쓰이지 않는다.
     */
    public List<Long> peekWaiting(long scheduleId, int count) {
        if (count <= 0) {
            return List.of();
        }
        Set<String> members = redis.opsForZSet().range(QueueKeys.waiting(scheduleId), 0, count - 1L);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(Long::valueOf).toList();
    }

    /**
     * 입장시킨다 — <b>활성에 넣고 나서</b> 대기에서 뺀다.
     *
     * <h2>이 순서가 설계의 핵심이다</h2>
     * 두 명령 사이에서 프로세스가 죽으면 사용자는 {@code active}와 {@code waiting} <b>양쪽에</b>
     * 남는다. 상태 조회가 {@code active}를 먼저 보므로 사용자는 정상 입장하고, 남은 중복은 다음
     * 틱이 "이미 활성인 멤버는 대기에서만 지운다"로 치운다. 손해는 그 틱의 입장 슬롯 몇 개뿐이다.
     * <p>
     * 거꾸로 하면({@code ZREM}을 먼저) 양쪽에서 사라져 <b>선두 몇 명이 조용히 맨 뒤로 간다.</b>
     * 사용자에게 설명할 수 없고 로그에도 남지 않는다. 그래서 멱등성 방향을 at-most-once가 아니라
     * <b>at-least-once로 고정</b>한다 — 명령 두 개의 순서를 바꾸는 것뿐인데 실패 모드가 "무해"와
     * "사고"로 갈린다.
     * <p>
     * 파이프라인으로 묶는 것은 원자성이 아니라 왕복 절약이다(1회로 줄어 끊길 창이 절반이 된다).
     * Lua를 쓰지 않는 이유는 Lua도 "프로세스가 죽는 것"은 못 막고, 그 0.3ms 창에 끊긴 결과가
     * 이미 무해하기 때문이다.
     */
    public void admit(long scheduleId, Collection<Long> memberIds, Instant passExpiresAt) {
        if (memberIds.isEmpty()) {
            return;
        }
        byte[] activeKey = raw(QueueKeys.active(scheduleId));
        byte[] waitingKey = raw(QueueKeys.waiting(scheduleId));
        double expiry = passExpiresAt.toEpochMilli();
        long ttlSeconds = properties.keyTtl().toSeconds();

        Set<Tuple> tuples = new LinkedHashSet<>();
        byte[][] rawMembers = new byte[memberIds.size()][];
        int index = 0;
        for (Long memberId : memberIds) {
            byte[] rawMember = raw(member(memberId));
            tuples.add(new DefaultTuple(rawMember, expiry));
            rawMembers[index++] = rawMember;
        }

        redis.executePipelined((RedisCallback<Object>) connection -> {
            connection.zSetCommands().zAdd(activeKey, tuples, ZAddArgs.empty());
            connection.zSetCommands().zRem(waitingKey, rawMembers);
            connection.keyCommands().expire(activeKey, ttlSeconds);
            connection.keyCommands().expire(waitingKey, ttlSeconds);
            return null;
        });
    }

    /**
     * 만료된 입장권을 수거한다. 명령 하나, O(log N + M).
     * <p>
     * {@code now}와 만료 시각이 같은 경우도 만료로 본다(경계 포함). 서비스의 유효성 판단과
     * 같은 기준이어야 "조회는 만료인데 수거는 안 되는" 틈이 생기지 않는다.
     *
     * @return 수거한 인원
     */
    public long removeExpiredPasses(long scheduleId, Instant now) {
        Long removed = redis.opsForZSet().removeRangeByScore(
                QueueKeys.active(scheduleId), Double.NEGATIVE_INFINITY, now.toEpochMilli());
        return orZero(removed);
    }

    /**
     * 입장권 만료 시각을 연장한다 — <b>이미 입장한 사람에게만, 더 늦은 시각으로만</b>
     * ({@code ZADD XX GT CH}).
     * <ul>
     *   <li>{@code XX}: 없는 멤버를 추가하지 않는다. 방금 수거된 사람이 연장으로 되살아나면
     *       만료가 무의미해진다</li>
     *   <li>{@code GT}: 기존 score보다 클 때만 갱신한다. 순서가 뒤바뀌어 도착한 요청이 만료 시각을
     *       되감지 못한다</li>
     *   <li>{@code CH}: 반환값을 "추가된 수"가 아니라 "변경된 수"로 바꾼다. {@code XX}에서는
     *       추가가 없으니 이게 없으면 성공과 실패를 구분할 수 없다</li>
     * </ul>
     * {@code ZSetOperations}에 {@code GT}가 없어서 저수준으로 내려간다.
     *
     * @return 실제로 연장됐으면 {@code true}
     */
    public boolean extendPass(long scheduleId, long memberId, Instant newExpiresAt) {
        byte[] key = raw(QueueKeys.active(scheduleId));
        byte[] rawMember = raw(member(memberId));
        double expiry = newExpiresAt.toEpochMilli();

        Boolean changed = redis.execute((RedisCallback<Boolean>) connection ->
                connection.zSetCommands().zAdd(key, expiry, rawMember, ZAddArgs.empty().xx().gt().ch()));
        return Boolean.TRUE.equals(changed);
    }

    /**
     * 대기열에서 빼고 입장권도 버린다. 없는 항목을 지워도 조용히 통과한다(멱등).
     * <p>
     * 양쪽을 같이 지우는 이유: 이탈은 "이 회차에서 나간다"는 뜻이고, 한쪽만 남으면 다음 진입에서
     * 상태가 섞인다.
     */
    public void leave(long scheduleId, long memberId) {
        String value = member(memberId);
        redis.opsForZSet().remove(QueueKeys.waiting(scheduleId), value);
        redis.opsForZSet().remove(QueueKeys.active(scheduleId), value);
    }

    /** 해당 회차의 큐를 통째로 지운다. 테스트 격리와 운영 확인용. */
    public void deleteQueue(long scheduleId) {
        redis.delete(List.of(QueueKeys.waiting(scheduleId), QueueKeys.active(scheduleId)));
    }

    private static String member(long memberId) {
        return String.valueOf(memberId);
    }

    private static byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
