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
 * <p>전략별 설정 클래스를 {@code @ConditionalOnProperty}로 흩지 않고 switch 하나로 모았다.
 * 전략은 "SecurityContext를 어디서 읽는가 + 로그인 때 무엇을 남기는가 + 세션 정책"이 한 세트라서
 * 빈마다 조건을 달면 조합이 어긋난 상태(예: STATELESS인데 세션 저장소를 쓰는)로도 기동이 된다.
 * 한곳에 모으면 컴파일러가 enum 분기 누락을 잡아주고, 비교가 끝난 뒤 지울 때도
 * case 한 줄 + 파일 하나만 지우면 된다.
 *
 * <p>세션 정책(SessionCreationPolicy)은 {@link SecurityConfig}가 같은 프로퍼티를 보고 정한다.
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
            // Security 쪽에서는 구분할 것이 없다.
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
