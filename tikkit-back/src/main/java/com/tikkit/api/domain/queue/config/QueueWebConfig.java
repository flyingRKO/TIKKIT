package com.tikkit.api.domain.queue.config;

import com.tikkit.api.domain.queue.service.QueueGateway;
import com.tikkit.api.domain.queue.web.QueuePassInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 입장권 인터셉터 등록 (Task 031).
 *
 * <h2>왜 WebConfig를 건드리지 않았나</h2>
 * {@code common/config/WebConfig}에 등록하면 {@code common -> domain/queue} 의존이 새로 생긴다.
 * 지금은 역방향만 있다({@code ReservationExpiryScheduler}가 {@code CacheNames}를 쓴다).
 * Spring은 {@code WebMvcConfigurer} 빈을 여러 개 병합하므로, 따로 만들면 CORS 설정은 그대로
 * 살아있고 {@code WebConfig}의 diff가 0이 된다.
 *
 * <h2>조건을 걸지 않은 이유</h2>
 * 대기열이 꺼진 환경에서도 인터셉터가 등록되지만, {@code DisabledQueueGateway}가 즉시 통과시키므로
 * 하는 일이 없다. 조건을 걸어 등록 자체를 막는 것과 결과가 같은데, <b>분기가 한 곳
 * ({@code QueueStrategyConfig})에만 있는 쪽이 낫다</b> — 조건이 두 곳에 흩어지면 한쪽만 고쳐서
 * "보호가 조용히 사라지는" 상태를 만들 수 있다. 비용은 좌석 조회마다 메서드 호출 한 번이다.
 */
@Configuration
public class QueueWebConfig implements WebMvcConfigurer {

    private final QueueGateway queueGateway;

    public QueueWebConfig(QueueGateway queueGateway) {
        this.queueGateway = queueGateway;
    }

    /**
     * 좌석 배치도 조회만 막는다.
     *
     * <p>{@code /ticket-grades}는 제외한다 — 응답이 수백 바이트이고 {@code gradePrices} 캐시가
     * 이미 흡수하는데다, <b>대기열 화면이 공연·가격을 보여주려면 그걸 불러야 한다.</b>
     * 막으면 줄 서 있는 사람이 자기가 뭘 기다리는지 볼 수 없다.
     *
     * <p>{@code POST /api/v1/reservations}는 여기 넣을 수 없다 — {@code scheduleId}가 요청 본문에
     * 있어서 인터셉터가 읽을 수 없다. {@code ReservationService}가 가드를 직접 호출한다.
     *
     * <p>결제·취소도 넣지 않는다. <b>이미 좌석을 잡은 사람은 입장권이 만료돼도 거래를 끝낼 수
     * 있어야 한다</b> — 선점 홀드는 10분이고 입장권은 15분이라 정상 흐름에서는 안 겹치지만,
     * 겹치는 순간 사용자는 돈을 낼 수도 좌석을 돌려받을 수도 없는 상태가 된다.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new QueuePassInterceptor(queueGateway))
                .addPathPatterns("/api/v1/schedules/*/seats");
    }
}
