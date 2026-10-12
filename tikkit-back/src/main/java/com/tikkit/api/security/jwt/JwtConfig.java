package com.tikkit.api.security.jwt;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tikkit.api.security.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT 서명·검증 빈과 인증 필터 (Task 026).
 *
 * <p>설정 클래스 전체가 {@code session-store=jwt}일 때만 올라온다. 그래서 다른 전략에서는
 * 이 빈들이 컨텍스트에 아예 없고, 쓰는 쪽({@code AuthStrategyConfig}, {@code SecurityConfig})은
 * {@code ObjectProvider}로 받아 "있으면 쓴다"로 처리한다. {@code @Bean}이 null을 반환하게
 * 만드는 쪽은 피했다 — Spring이 NullBean으로 등록해서 직접 주입하는 곳에서 뒤늦게 깨진다.
 *
 * <p>HS256(대칭키)을 쓴다. 비대칭(RS256)은 "검증하는 쪽과 발급하는 쪽이 다를 때" 값을 하는데
 * 지금은 BE가 양쪽을 다 하므로 키 쌍 관리 비용만 늘어난다. BE가 여러 종류로 쪼개지거나 검증을
 * 외부에 맡겨야 하면 그때 바꾼다.
 *
 * <p>비밀키는 {@code .env}의 {@code JWT_SECRET}이다. HS256은 키가 해시 출력 길이
 * (256비트 = 32바이트) 이상이어야 하고, 짧으면 빈 생성 시점에 Nimbus가 예외를 던진다 —
 * 기동이 깨지는 편이 짧은 키로 도는 것보다 안전하다.
 */
@Configuration
@ConditionalOnProperty(prefix = "tikkit.auth", name = "session-store", havingValue = "jwt")
@RequiredArgsConstructor
public class JwtConfig {

    private final AuthProperties authProperties;

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter(JwtDecoder jwtDecoder) {
        return new JwtCookieAuthenticationFilter(jwtDecoder);
    }

    private SecretKeySpec secretKey() {
        return new SecretKeySpec(
                authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
