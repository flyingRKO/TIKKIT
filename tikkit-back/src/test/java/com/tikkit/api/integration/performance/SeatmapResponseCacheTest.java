package com.tikkit.api.integration.performance;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.support.AbstractRedisCacheContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 좌석맵 응답 바이트 캐시(Task 029 실험군)의 히트 경로를 고정한다.
 *
 * <h2>왜 이 테스트가 뒤늦게 생겼나</h2>
 * 이 캐시는 기본 비활성({@code tikkit.cache.seatmap-response-ttl=0})이라 테스트를 쓰지 않았고,
 * 그래서 <b>부하 측정에서야 버그가 드러났다.</b> 값이 {@code byte[]}인데 캐시의 값 직렬화가
 * Jackson + default typing이라, 넣을 때는 base64로 감싸지고 꺼낼 때
 * {@code get(key, byte[].class)}가 타입 검사에 걸려 터졌다. 미스 경로는 멀쩡해서
 * <b>히트할 때만 실패</b>했고, k6의 진짜 에러율이 27%로 찍혔다.
 * <p>
 * "기본값이 꺼져 있으니 테스트는 나중에"가 통하지 않는다는 기록으로 남긴다. 토글로 켜는 경로도
 * 켜면 도는 코드다.
 *
 * <p>TTL을 30초로 둔다 — 운영 후보값(1~2초)으로는 테스트 안에서 히트를 보장할 수 없다.
 */
@TestPropertySource(properties = "tikkit.cache.seatmap-response-ttl=30s")
class SeatmapResponseCacheTest extends AbstractRedisCacheContainerTest {

    @Autowired
    private MockMvc mockMvc;

    private Long scheduleId;

    @BeforeEach
    void setUpFixture() {
        scheduleId = createOnSaleGradeWithSeats(5).scheduleId();
    }

    @Test
    @DisplayName("히트와 미스가 같은 바이트를 내려준다 — FE가 두 경로를 구분할 수 없다")
    void 히트와_미스의_응답이_같다() throws Exception {
        byte[] miss = mockMvc.perform(get("/api/v1/schedules/{id}/seats", scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andReturn().getResponse().getContentAsByteArray();

        awaitCached(CacheNames.SEATMAP_RESPONSE, scheduleId);

        byte[] hit = mockMvc.perform(get("/api/v1/schedules/{id}/seats", scheduleId))
                .andExpect(status().isOk())
                // 히트 경로는 ResponseEntity<byte[]>로 내려가므로 Content-Type을 직접 박는다.
                // 이게 빠지면 브라우저가 JSON으로 읽지 않는다.
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith(MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andReturn().getResponse().getContentAsByteArray();

        // 바이트가 완전히 같아야 한다. 이게 "응답 계약이 안 바뀐다"의 정의다.
        assertThat(hit).isEqualTo(miss);
    }

    @Test
    @DisplayName("없는 회차는 바이트 캐시 경로에서도 404다 — 캐시가 예외를 삼키지 않는다")
    void 없는_회차는_404() throws Exception {
        mockMvc.perform(get("/api/v1/schedules/{id}/seats", scheduleId + 100_000L))
                .andExpect(status().isNotFound());

        assertThat(cacheManager.getCache(CacheNames.SEATMAP_RESPONSE).get(scheduleId + 100_000L)).isNull();
    }
}
