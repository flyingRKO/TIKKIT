package com.tikkit.api.integration.actuator;

import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * actuator 엔드포인트의 접근 제어와 지표명을 고정하는 테스트 (Task 025).
 * <p>
 * <b>지표명을 단정하는 이유</b>: Boot 4로 올리면서 Micrometer 자동설정 패키지가 바뀌었고
 * (docs/improvements/004_1-spring-boot-4-migration.md), 지표명이 함께 바뀌었는지 확인하는 일이
 * Task 025로 넘어왔다. Grafana 대시보드의 PromQL이 이 이름들에 직접 의존하므로, 이름이 바뀌면
 * 대시보드가 조용히 "No data"가 되는 대신 여기서 깨지게 만든다.
 */
@AutoConfigureMockMvc
class ActuatorEndpointAccessTest extends AbstractContainerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("health는 비로그인으로 열리고 UP을 응답한다")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("health가 DataSource 상태를 포함한다 — compose 헬스체크가 이걸 믿고 도메인 엔드포인트를 버렸다")
    void healthIncludesDatabase() {
        // show-details가 test 프로필에서는 기본값(never)이라 응답 본문에 components가 없다.
        // 대신 DataSource 헬스 기여자가 등록돼 있는지로 확인한다 — 이게 없으면 DB가 죽어도
        // health가 UP을 뱉어서 compose 헬스체크가 무의미해진다.
        assertThat(applicationContext.containsBean("dbHealthContributor")).isTrue();
    }

    @Test
    @DisplayName("prometheus는 비로그인으로 열리고 JVM·Hikari 지표를 포함한다")
    void prometheusExposesCoreMetrics() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("jvm_memory_used_bytes")
                // jvm_gc_pause_seconds는 단정하지 않는다 — GC가 실제로 한 번 일어난 뒤에
                // 등록되는 타이머라서 짧게 끝나는 테스트에서는 있을 때도 없을 때도 있다.
                // Grafana의 GC 패널은 이 지표를 쓰지만, 실제로 트래픽을 받는 앱에서는 반드시 나온다.
                .contains("jvm_threads_live_threads")
                .contains("process_cpu_usage")
                .contains("hikaricp_connections_active")
                .contains("hikaricp_connections_pending")
                .contains("hikaricp_connections_max");
    }

    @Test
    @DisplayName("http.server.requests에 _bucket 시리즈가 나온다 — p95를 histogram_quantile로 계산할 수 있다")
    void httpServerRequestsHasHistogramBuckets() throws Exception {
        // 버킷은 요청이 한 번이라도 기록된 뒤에 생긴다. 아무 엔드포인트나 먼저 때린다.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // percentiles-histogram을 끄면 이 시리즈가 사라지고 Grafana의 p95 패널이 전부 비게 된다.
        assertThat(body).contains("http_server_requests_seconds_bucket");
    }

    @Test
    @DisplayName("노출하지 않은 엔드포인트는 접근할 수 없다")
    void unexposedEndpointIsNotAccessible() throws Exception {
        // management.endpoints.access.default: none 이라 env 엔드포인트는 컨텍스트에 아예 없다.
        // 핸들러가 없으므로 SecurityConfig의 anyRequest().authenticated()에 걸려 401이 된다.
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }
}
