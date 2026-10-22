package com.tikkit.api.domain.queue;

/**
 * 대기열 Redis 키 (Task 031). 회차당 두 개가 전부다.
 *
 * <pre>
 * tikkit:queue:{12}:waiting   ZSET  member = memberId, score = 진입 epoch millis
 * tikkit:queue:{12}:active    ZSET  member = memberId, score = 입장권 만료 epoch millis
 * </pre>
 *
 * <h2>이름을 리터럴로 흩뿌리지 않는 이유</h2>
 * {@code CacheNames}와 같다 — 오타가 조용히 "다른 큐"를 만들고, 증상이 "대기열이 안 움직인다"로만
 * 나타난다. 예외도 로그도 없다.
 *
 * <h2>중괄호는 Redis Cluster hash tag다</h2>
 * {@code {12}}의 중괄호는 리터럴이다. Redis Cluster는 키 이름에 중괄호가 있으면 <b>그 안의
 * 문자열만</b>으로 슬롯을 계산하므로, 같은 회차의 두 키가 항상 같은 노드에 놓인다. 입장
 * 스케줄러가 {@code waiting}과 {@code active}를 한 흐름에서 같이 만지기 때문에 필요하다
 * (파이프라인은 여러 슬롯에 걸치면 쓸 수 없다).
 * <p>
 * 지금은 standalone이라 아무 영향이 없다. 그래도 지금 넣는 이유는 <b>나중에 넣는 건 공짜가
 * 아니기 때문</b>이다 — 운영 중에 키 포맷을 바꾸면 이미 줄 서 있는 사람들의 큐가 사라진다.
 *
 * <h2>활성 쪽이 SET이 아니라 ZSET인 이유</h2>
 * 로드맵은 "활성 세트 + TTL 토큰"이었다. 그대로 만들면 토큰 키가 만료돼도 SET에는 멤버가
 * <b>영구히</b> 남아 {@code SCARD}가 틀린 값을 준다. 유령을 치우려면 SET 전체를 순회해 토큰 키의
 * 존재를 확인해야 한다(O(N) 왕복).
 * <p>
 * score에 만료 시각을 넣으면 그게 전부 사라진다 — 검증은 {@code ZSCORE} 왕복 1회, 만료 일괄
 * 수거는 {@code ZREMRANGEBYSCORE} 명령 1개, 연장은 {@code ZADD GT} 명령 1개다. 테스트에서
 * 만료를 재현할 때 {@code sleep} 없이 과거 score를 심을 수 있는 것도 이 설계의 덕이다.
 */
public final class QueueKeys {

    private static final String PREFIX = "tikkit:queue:";

    private QueueKeys() {
    }

    /** 대기 중인 회원 (score = 진입 시각). */
    public static String waiting(long scheduleId) {
        return PREFIX + "{" + scheduleId + "}:waiting";
    }

    /** 입장한 회원 (score = 입장권 만료 시각). */
    public static String active(long scheduleId) {
        return PREFIX + "{" + scheduleId + "}:active";
    }
}
