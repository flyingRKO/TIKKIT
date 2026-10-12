package com.tikkit.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 상태를 어디에 두는지 고르는 설정 (Task 026).
 *
 * <p>BE가 2대 이상이면 {@code MEMORY}는 세션 불일치가 난다 — A에서 로그인한 뒤 B로 간 요청이
 * 401이 된다. 운영은 {@code REDIS}를 쓰고, {@code MEMORY}는 Redis 없이 돌아야 하는
 * 개발·테스트용으로 남겨뒀다({@code docs/improvements/003-redis-distributed-lock.md} 3절의 결정).
 *
 * <p>Task 026에서 JWT(stateless)도 같은 토글로 구현해 비교했고, 측정 후 Redis 세션을 채택하면서
 * 제거했다. 근거는 {@code docs/improvements/005-session-scaling.md}에 있다.
 *
 * <p>{@code @Value}가 아니라 {@code @ConfigurationProperties}를 쓴 이유는 enum 바인딩이다 —
 * {@code @Value}는 문자열을 enum으로 바꿀 때 상수명이 정확히 같아야 하는데,
 * {@code @ConfigurationProperties}는 완화된 바인딩이라 yml에 소문자({@code redis})로 써도
 * {@code REDIS}로 들어온다.
 */
@ConfigurationProperties(prefix = "tikkit.auth")
public record AuthProperties(SessionStore sessionStore) {

    public enum SessionStore {
        /** 서블릿 컨테이너의 HttpSession (= JVM 메모리). 인스턴스가 1대일 때만 성립한다. */
        MEMORY,
        /** Spring Session Data Redis. HttpSession 구현만 Redis로 갈아끼운다. 운영 기본값. */
        REDIS
    }
}
