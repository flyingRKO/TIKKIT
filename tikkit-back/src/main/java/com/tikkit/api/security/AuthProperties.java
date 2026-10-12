package com.tikkit.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 인증 상태를 어디에 두는지 고르는 설정 (Task 026).
 *
 * <p>BE를 2대로 늘리면 세션이 서버 메모리에만 있어서 A에서 로그인한 뒤 B로 간 요청이 401이 된다.
 * 해결책을 수치로 비교하기 위해 저장소를 런타임에 갈아끼울 수 있게 만들었다.
 * 비교가 끝나면 채택한 하나만 남기고 이 프로퍼티와 나머지 분기는 지운다.
 *
 * <p>{@code @Value}가 아니라 {@code @ConfigurationProperties}를 쓴 이유는 enum 바인딩이다 —
 * {@code @Value}는 문자열을 enum으로 바꿀 때 상수명이 정확히 같아야 하는데,
 * {@code @ConfigurationProperties}는 완화된 바인딩이라 yml에 소문자({@code redis})로 써도
 * {@code REDIS}로 들어온다. {@code Duration}도 {@code 30m} 표기를 그대로 받는다.
 *
 * @param sessionStore 저장 전략
 * @param jwt JWT 전략에서만 쓰는 설정. 다른 전략에서는 값이 비어 있어도 된다.
 */
@ConfigurationProperties(prefix = "tikkit.auth")
public record AuthProperties(SessionStore sessionStore, Jwt jwt) {

    public enum SessionStore {
        /** 서블릿 컨테이너의 HttpSession (= JVM 메모리). 인스턴스가 1대일 때만 성립한다. */
        MEMORY,
        /** Spring Session Data Redis. HttpSession 구현만 Redis로 갈아끼운다. */
        REDIS,
        /** 서명된 토큰을 쿠키로 내린다. 서버가 상태를 안 들고 있어서 강제 로그아웃이 불가능하다. */
        JWT
    }

    /**
     * @param secret HS256 대칭키. 32바이트(256비트) 이상이어야 하고, 짧으면 기동이 깨진다.
     * @param ttl 토큰 유효기간. 세션 타임아웃과 같은 값을 주더라도 의미가 다르다 —
     *            세션은 요청마다 밀리는 슬라이딩이고 이쪽은 발급 기준 절대 시각이다.
     */
    public record Jwt(String secret, Duration ttl) {
    }
}
