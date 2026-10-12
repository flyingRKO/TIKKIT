package com.tikkit.api.security.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * HttpSession에 SecurityContext를 저장하는 구현. MEMORY와 REDIS가 **같은 코드를 쓴다**.
 *
 * <p>Spring Session이 HttpSession 구현만 Redis로 갈아끼우기 때문이다 — 세션 API를 부르는 이 코드는
 * 저장소가 JVM 메모리인지 Redis인지 모른다. 이게 Redis 세션 방식의 핵심 장점이고,
 * 비교표의 "코드 변경량" 칸에서 JWT와 갈리는 지점이다.
 *
 * <p>원래 {@code AuthController.login/logout}에 인라인으로 있던 코드를 그대로 옮겼다 (Task 026).
 */
@RequiredArgsConstructor
public class SessionLoginCredentialWriter implements LoginCredentialWriter {

    private final SecurityContextRepository securityContextRepository;

    @Override
    public void write(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        // 세션 고정(session fixation) 공격 방지: 로그인 전부터 세션이 있었다면(예: 익명 상태에서 발급된 쿠키를
        // 공격자가 미리 심어두는 경우) 그 세션 ID를 새로 발급한다. 세션이 아직 없으면 changeSessionId()가
        // 예외를 던지므로(HttpServletRequest 스펙상 대상 세션이 있어야 함) 호출하지 않는다 — 이 경우 바로 아래
        // saveContext()가 처음부터 새 랜덤 ID로 세션을 만들어주므로 고정 위험이 없다.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Spring Security 6부터 커스텀 로그인 엔드포인트는 SecurityContext를 세션에 명시적으로 저장해야 한다
        // (필터 체인의 자동 저장은 SecurityContextHolderFilter가 "요청 시작 시점"에만 세션→컨텍스트로 복원해줄 뿐,
        //  이 요청 안에서 새로 만든 컨텍스트를 세션에 써주지는 않는다).
        securityContextRepository.saveContext(context, request, response);
    }

    @Override
    public void clear(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        // 세션 무효화 + SecurityContext 비우기 + (설정에 따라) 쿠키 제거까지 한 번에 한다.
        // 세션이 Redis에 있으면 Redis 키가 지워진다 — 서버가 상태를 들고 있어서 "강제로 끊을 수 있다".
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}
