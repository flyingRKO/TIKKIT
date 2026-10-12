package com.tikkit.api.security.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

/**
 * 로그인 성공을 "다음 요청에도 알아볼 수 있는 형태"로 남기고, 로그아웃에서 그것을 지운다 (Task 026).
 *
 * <p>이 인터페이스를 뽑은 이유는 {@code AuthController}에 전략 분기를 넣지 않기 위해서다.
 * 세션 방식은 세션에 SecurityContext를 저장하고, Task 026에서 비교했던 JWT 방식은 토큰을 서명해
 * 쿠키로 내렸다 — 컨트롤러가 알아야 할 일이 아니다. 컨트롤러는 "인증됐다"는 사실만 넘긴다.
 *
 * <p>구현이 지금 하나뿐이라 추상화가 과해 보일 수 있다. 남겨둔 이유는 이게 "전략을 바꿀 때
 * 컨트롤러를 안 건드린다"는 경계이고, Task 026에서 실제로 그 값을 했다는 것이다 —
 * JWT를 붙이고 떼는 동안 {@code AuthController}는 한 줄도 바뀌지 않았다.
 *
 * <p>구현을 고르는 곳은 {@link com.tikkit.api.security.AuthStrategyConfig} 한 곳뿐이다.
 */
public interface LoginCredentialWriter {

    void write(Authentication authentication, HttpServletRequest request, HttpServletResponse response);

    void clear(Authentication authentication, HttpServletRequest request, HttpServletResponse response);
}
