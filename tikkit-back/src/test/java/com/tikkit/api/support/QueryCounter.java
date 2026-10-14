package com.tikkit.api.support;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import java.util.function.Supplier;

/**
 * 한 번의 서비스 호출이 DB로 보낸 JDBC 문장 수를 세는 테스트 하네스 (Task 028).
 * <p>
 * N+1은 응답 시간 지표로는 안 보인다. 쿼리 하나가 0.3 ms면 10개로 늘어나도 3 ms고, p95가
 * 2~7 ms인 서비스에서는 그 차이가 측정 노이즈에 묻힌다. 그래서 "몇 문장이 나갔나"를 직접 센다
 * ({@code docs/improvements/006-performance-baseline.md} 10절이 Task 028에 넘긴 요구).
 * <p>
 * 외부 의존성(p6spy, datasource-proxy)을 추가하지 않고 Hibernate가 이미 들고 있는
 * {@link Statistics}를 쓴다. {@code hibernate.generate_statistics}가 켜져 있어야 하고,
 * 그 설정은 {@code application-test.yml}에만 있다.
 * <p>
 * <b>트랜잭션 밖에서 써야 한다.</b> 테스트에 {@code @Transactional}이 붙어 있으면 세션 하나가
 * 끝까지 열려 있어서 두 번째 조회가 1차 캐시에 맞고 flush 시점도 운영과 달라진다. 그래서
 * 이 하네스를 쓰는 테스트는 {@code AbstractConcurrencyTest}(비트랜잭션 + truncate)를 베이스로 쓴다.
 * <p>
 * {@code JdbcTemplate} 같은 Hibernate 밖 경로는 세지 않는다 — 픽스처를 JdbcTemplate으로 깔아도
 * 카운트에 섞이지 않는다는 뜻이다.
 */
public class QueryCounter {

    private final Statistics statistics;

    public QueryCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        if (!statistics.isStatisticsEnabled()) {
            throw new IllegalStateException(
                    "hibernate.generate_statistics가 꺼져 있어 쿼리 수를 셀 수 없다. application-test.yml을 확인한다.");
        }
    }

    /** 반환값이 없는 호출의 문장 수. */
    public long count(Runnable action) {
        return countReturning(() -> {
            action.run();
            return null;
        }).queries();
    }

    /**
     * 반환값과 문장 수를 함께 받는다.
     * <p>
     * {@code count(Runnable)}과 이름을 나눠 둔 이유는, 값을 돌려주는 람다를 넘길 때
     * {@code Runnable}과 {@code Supplier} 사이에서 오버로드 모호성이 생기기 때문이다.
     */
    public <T> Counted<T> countReturning(Supplier<T> action) {
        statistics.clear();
        T value = action.get();
        return new Counted<>(value, statistics.getPrepareStatementCount());
    }

    /** 호출 결과와 그 호출이 보낸 문장 수. */
    public record Counted<T>(T value, long queries) {
    }
}
