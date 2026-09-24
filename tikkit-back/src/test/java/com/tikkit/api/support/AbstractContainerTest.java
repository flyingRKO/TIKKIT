package com.tikkit.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 레포지토리·통합 테스트 공통 베이스.
 * 테스트 JVM 전체에서 컨테이너 하나를 재사용하는 싱글턴 패턴을 쓴다 (클래스마다 새로 띄우지 않는다).
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractContainerTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15")
                    .withDatabaseName("tikkit_test")
                    .withUsername("tikkit_test")
                    .withPassword("tikkit_test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}