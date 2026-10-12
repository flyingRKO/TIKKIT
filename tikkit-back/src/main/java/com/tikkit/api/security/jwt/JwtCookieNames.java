package com.tikkit.api.security.jwt;

/**
 * JWT를 담는 쿠키의 이름 (Task 026).
 *
 * <p>세션 방식과 같은 {@code JSESSIONID}를 쓴다. 토큰을 세션 ID처럼 보이게 하려는 게 아니라
 * <b>비교 측정의 변수를 하나로 묶기 위한 것</b>이다 — FE({@code tikkit-front/lib/session.ts},
 * {@code lib/api/auth.ts})가 이 이름을 하드코딩하고 있어서 이름을 바꾸면 FE도 같이 고쳐야 하고,
 * 그러면 전략별 응답 지연에 FE 변경분이 섞인다.
 *
 * <p>Authorization 헤더가 아니라 쿠키로 내리는 이유도 같다. 브라우저는 BE를 직접 호출하지 않고
 * Next 서버가 쿠키를 중계하는 구조라({@code lib/api/client.ts}), 헤더로 바꾸면 FE의 중계 코드를
 * 전부 고쳐야 한다. 덤으로 CSRF 표면도 그대로다 — 토큰이 BE 도메인이 아니라 Next 도메인 쿠키에
 * 저장되고 브라우저가 BE를 직접 못 부르므로, {@code SecurityConfig}의 {@code csrf.disable()}
 * 근거("CSRF 방어는 Next Server Action의 Origin 검증에 위임")가 그대로 유효하다.
 */
public final class JwtCookieNames {

    public static final String ACCESS_TOKEN = "JSESSIONID";

    private JwtCookieNames() {
    }
}
