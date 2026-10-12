package com.tikkit.api.security.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

/**
 * 로그인 성공을 "다음 요청에도 알아볼 수 있는 형태"로 남기고, 로그아웃에서 그것을 지운다 (Task 026).
 *
 * <p>이 인터페이스를 뽑은 이유는 {@code AuthController}에 전략 분기를 넣지 않기 위해서다.
 * 세션 방식은 세션에 SecurityContext를 저장하고, JWT 방식은 토큰을 서명해 쿠키로 내린다 —
 * 컨트롤러가 알아야 할 일이 아니다. 컨트롤러는 "인증됐다"는 사실만 넘긴다.
 *
 * <p>구현을 고르는 곳은 {@link com.tikkit.api.security.AuthStrategyConfig} 한 곳뿐이다.
 */
public interface LoginCredentialWriter {

    void write(Authentication authentication, HttpServletRequest request, HttpServletResponse response);

    void clear(Authentication authentication, HttpServletRequest request, HttpServletResponse response);
}
