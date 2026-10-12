package com.tikkit.api.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 배치 활성화. test 프로필에서는 꺼서 통합 테스트에 배치가 끼어들지 않게 한다.
 *
 * <h2>ShedLock (Task 026)</h2>
 * BE가 2대가 되면서 배치가 양쪽에서 돌기 시작했다. 실측 로그:
 * <pre>
 * 10:33:06.662 backend2  선점 만료 배치 실행 — 반환된 좌석 수: 2
 * 10:33:06.694 backend1  선점 만료 배치 실행 — 반환된 좌석 수: 0
 * </pre>
 *
 * <p><b>데이터는 깨지지 않는다.</b> 만료 쿼리 두 개가 모두 멱등이다 —
 * {@code expirePendingReservations}는 {@code WHERE status='PENDING' AND expires_at <= :now},
 * {@code recalculateDerivedFields}는 {@code WHERE ... IS DISTINCT FROM ...}을 걸어서
 * 먼저 돈 쪽이 일을 다 하고 두 번째는 0건을 반환한다(위 로그가 그 증거다).
 *
 * <p>그래도 락을 넣는 이유 셋:
 * <ol>
 *   <li>같은 일을 두 번 하면서 DB 왕복과 CTE 스캔이 두 배가 된다</li>
 *   <li>로그에 같은 배치가 두 줄씩 찍혀서 "반환된 좌석 수: 0"을 보고 "만료가 안 됐다"로
 *       오독하게 된다. 인스턴스가 늘면 더 심해진다</li>
 *   <li><b>다음에 추가될 배치가 멱등일 보장이 없다.</b> 알림 발송·정산 같은 작업은 두 번 돌면
 *       그대로 사고다. 지금 쿼리가 멱등인 것은 Task 019가 조건부 UPDATE로 설계한 결과이고,
 *       그 설계를 모르는 사람이 배치를 하나 더 붙일 때 안전망이 필요하다</li>
 * </ol>
 *
 * <p>{@code usingDbTime()}을 쓴다. 락 유효 시각을 애플리케이션 시계가 아니라 <b>DB 서버 시계</b>로
 * 판정해서 노드 간 시계 차이를 제거한다 — ShedLock README가 경고하는 함정("노드 시계 차이가
 * lockAtLeastFor보다 크면 중복 실행된다")을 원천에서 없애는 방법이고, JDBC provider만 제공한다.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
@Profile("!test")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .usingDbTime()
                        .build());
    }
}
