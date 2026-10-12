package com.tikkit.api.security;

import com.tikkit.api.security.login.LoginCredentialWriter;
import com.tikkit.api.security.login.SessionLoginCredentialWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * 인증 상태 저장 전략을 고르는 **유일한** 자리 (Task 026).
 *
 * <p>전략별 설정 클래스를 {@code @ConditionalOnProperty}로 흩지 않고 switch로 모았다.
 * 전략은 "SecurityContext를 어디서 읽는가 + 로그인 때 무엇을 남기는가 + 세션 정책"이 한 세트라서
 * 빈마다 조건을 달면 조합이 어긋난 상태로도 기동이 된다. 한곳에 모으면 컴파일러가 enum 분기
 * 누락을 잡아준다.
 *
 * <p>이 구조가 실제로 값을 했다. Task 026에서 JWT를 세 번째 전략으로 붙였다가 측정 후 제거했는데,
 * 정리 커밋에서 건드린 운영 코드가 이 파일의 case 두 줄과 {@code SecurityConfig}의 if 블록
 * 하나뿐이었다. 나머지는 파일 삭제로 끝났다.
 *
 * <p>Redis 연결 설정은 {@code SessionRedisConfig}가 따로 맡는다 — 전략이 켜질 때만
 * 연결 팩토리를 만들어야 해서 조건부 설정 클래스가 필요하다.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
@RequiredArgsConstructor
public class AuthStrategyConfig {

    private final AuthProperties authProperties;

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return switch (authProperties.sessionStore()) {
            // MEMORY와 REDIS가 같은 구현이다. Spring Session이 HttpSession 구현만 갈아끼우므로
            // Security 쪽에서는 구분할 것이 없다 — 이게 Redis 세션을 고른 이유 중 하나다.
            case MEMORY, REDIS -> new HttpSessionSecurityContextRepository();
        };
    }

    @Bean
    public LoginCredentialWriter loginCredentialWriter(SecurityContextRepository securityContextRepository) {
        return switch (authProperties.sessionStore()) {
            case MEMORY, REDIS -> new SessionLoginCredentialWriter(securityContextRepository);
        };
    }
}
