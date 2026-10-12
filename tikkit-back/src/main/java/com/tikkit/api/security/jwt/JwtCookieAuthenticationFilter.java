package com.tikkit.api.security.jwt;

import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.security.MemberDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * 쿠키에 담긴 JWT를 검증해 SecurityContext를 채운다 (Task 026).
 *
 * <p>세션 방식에서 {@code SecurityContextHolderFilter}가 하던 일(요청 시작 시점에 저장소에서
 * 컨텍스트를 복원)을 대신한다. 다른 점은 <b>저장소를 조회하지 않는다</b>는 것이다 —
 * 서명만 확인하고 클레임으로 주체를 만든다. 그래서 Redis 왕복도, DB 조회도 없다.
 *
 * <p>토큰이 없거나 깨졌으면 아무것도 하지 않고 넘긴다. 401을 내는 것은 이 필터가 아니라
 * 뒤쪽 {@code AuthorizationFilter}와 {@code ApiAuthenticationEntryPoint}의 일이다 —
 * 여기서 끊으면 permitAll 경로(공연 목록 등)까지 토큰을 요구하게 된다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

    private final JwtDecoder jwtDecoder;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        readToken(request).ifPresent(token -> {
            try {
                Jwt jwt = jwtDecoder.decode(token);
                MemberDetails member = new MemberDetails(
                        Long.valueOf(jwt.getSubject()),
                        jwt.getClaimAsString("email"),
                        MemberRole.valueOf(jwt.getClaimAsString("role")));

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        member, null, member.getAuthorities()));
                SecurityContextHolder.setContext(context);
            } catch (JwtException | IllegalArgumentException e) {
                // 만료·위조·형식 오류를 전부 "인증 안 됨"으로 처리한다. 사유를 응답에 담지 않는 것은
                // 공격자에게 토큰 구조를 알려주지 않기 위한 것이고, 로그로만 남긴다.
                log.debug("JWT 검증 실패 — 인증 없이 진행한다: {}", e.getMessage());
            }
        });

        chain.doFilter(request, response);
    }

    private Optional<String> readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> JwtCookieNames.ACCESS_TOKEN.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }
}
