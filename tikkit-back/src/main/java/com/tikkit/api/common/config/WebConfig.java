package com.tikkit.api.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 요청의 CORS 설정.
 *
 * 허용 origin을 하드코딩하지 않고 tikkit.cors.allowed-origins로 뽑았다 (Task 024).
 * String[] 타입으로 받으면 스프링이 콤마로 끊어주므로 다중 origin이 그대로 된다.
 *
 * 참고: 브라우저는 BE(8080)를 직접 호출하지 않고 Next 서버만 호출하므로 이 설정이 실제로
 * 발동하는 경로는 지금 없다. 그래도 값을 외부화한 이유는 "http://localhost:3000"이 이미지에
 * 구워진 채로 운영에 올라가는 걸 막기 위한 것이다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${tikkit.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
