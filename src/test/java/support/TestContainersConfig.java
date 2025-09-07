package support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
/**
 * 테스트 실행 시 Postgres 컨테이너를 자동 기동하고
 * spring.datasource.* 를 자동으로 주입합니다.
 * (Docker Desktop만 켜져 있으면 별도 docker run/compose 불필요)
 */
@TestConfiguration
public class TestContainersConfig {
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("tikkit_test")
                .withUsername("tester")
                .withPassword("tester");
        // 재사용하고 싶으면 아래와 같이:
        // .withReuse(true) + ~/.testcontainers.properties에 testcontainers.reuse.enable=true
    }
}
