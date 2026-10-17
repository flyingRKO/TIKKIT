package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.common.config.CacheProperties;
import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 좌석맵 <b>응답 바이트</b> 캐시 (Task 029 실험군). 기본적으로 꺼져 있다.
 *
 * <h2>왜 이게 따로 필요한가</h2>
 * 007이 Task 029로 이렇게 넘겼다.
 * <blockquote>
 * 캐시의 이득은 DB 시간이 아니라 좌석맵 237.9KB의 <b>직렬화 비용</b>에서 나올 가능성이 크다.
 * 무엇이 줄어드는지 먼저 정하고 재야 한다.
 * </blockquote>
 * 그런데 서비스 레이어 {@code @Cacheable}로는 그 비용을 잡을 수 없다. 캐시가 돌려주는 건
 * {@code List<ScheduleSeatResponse>}이고, 컨트롤러가 그걸 반환하면 Jackson이 2,000건을 다시
 * JSON으로 쓴다. <b>직렬화를 건너뛰려면 직렬화된 결과를 캐시해서 바이트째로 내려줘야 한다.</b>
 * 이 클래스가 그걸 한다.
 *
 * <h2>왜 운영에서 켜지 않나</h2>
 * 캐시 값에 {@code status}가 그대로 들어 있어서 TTL만큼 stale해진다. 오픈런에서 이미 HELD인
 * 좌석을 AVAILABLE로 보여주면 사용자가 그 좌석을 고르고 선점 요청이 409로 튕긴다 — 서버는
 * 정상이지만 사용자 경험은 "자리가 있다고 해서 눌렀는데 실패"가 된다. 그래서 기본값 0(비활성)이고,
 * 이 스위치의 목적은 <b>직렬화 비용의 상한을 측정해 기각 근거를 남기는 것</b>이다.
 *
 * <h2>바이트를 캐시해도 응답 계약은 같다</h2>
 * {@code ApiResponse.success(seats)}를 통째로 직렬화해 담으므로, 캐시 히트든 미스든 클라이언트가
 * 받는 바이트가 동일하다. FE는 차이를 알 수 없다.
 *
 * <p>캐시를 {@code CacheManager}로 직접 다룬다. {@code @Cacheable}을 쓰면 TTL이 프로퍼티로
 * 정해지는 캐시를 조건부로만 등록하기 어렵고, 꺼져 있을 때 어노테이션이 그냥 통과하는 것과
 * "캐시 자체가 없는 것"을 구분하기도 어렵다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatmapResponseCache {

    private final CacheManager cacheManager;
    private final CacheProperties cacheProperties;
    private final ObjectMapper objectMapper;

    public boolean enabled() {
        return cacheProperties.seatmapResponseCacheEnabled();
    }

    /**
     * 캐시된 응답 바이트를 꺼낸다. 없으면 {@code null}.
     */
    public byte[] find(Long scheduleId) {
        Cache cache = cacheManager.getCache(CacheNames.SEATMAP_RESPONSE);
        if (cache == null) {
            return null;
        }
        return cache.get(scheduleId, byte[].class);
    }

    /**
     * 좌석 목록을 {@code ApiResponse}로 감싸 직렬화하고 캐시에 넣은 뒤 그 바이트를 돌려준다.
     */
    public byte[] store(Long scheduleId, List<ScheduleSeatResponse> seats) {
        byte[] body = objectMapper.writeValueAsBytes(ApiResponse.success(seats));
        Cache cache = cacheManager.getCache(CacheNames.SEATMAP_RESPONSE);
        if (cache != null) {
            cache.put(scheduleId, body);
        }
        return body;
    }
}
