package com.tikkit.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 상태를 어디에 두는지 고르는 설정 (Task 026).
 *
 * <p>BE를 2대로 늘리면 세션이 서버 메모리에만 있어서 A에서 로그인한 뒤 B로 간 요청이 401이 된다.
 * 해결책을 수치로 비교하기 위해 저장소를 런타임에 갈아끼울 수 있게 만들었다.
 * 비교가 끝나면 채택한 하나만 남기고 이 프로퍼티와 나머지 분기는 지운다.
 *
 * <p>@Value가 아니라 @ConfigurationProperties를 쓴 이유는 enum 바인딩이다 —
 * @Value는 문자열을 enum으로 바꿀 때 상수명이 정확히 같아야 하는데,
 * @ConfigurationProperties는 완화된 바인딩이라 yml에 소문자(redis)로 써도 REDIS로 들어온다.
 */
@ConfigurationProperties(prefix = "tikkit.auth")
public record AuthProperties(SessionStore sessionStore) {

    public enum SessionStore {
        /** 서블릿 컨테이너의 HttpSession (= JVM 메모리). 인스턴스가 1대일 때만 성립한다. */
        MEMORY,
        /** Spring Session Data Redis. HttpSession 구현만 Redis로 갈아끼운다. */
        REDIS
    }
}
