package com.tikkit.api.common.aop;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;

/**
 * {@link DistributedLock}이 붙은 메서드를 Redis 분산 락으로 감싼다 (Task 020 비교 실험 전용).
 *
 * <h2>왜 @Order(0)인가 — 위아래 양쪽에 경계가 있다</h2>
 * <b>위쪽 경계(트랜잭션):</b> {@code @EnableTransactionManagement}의 {@code order} 기본값은
 * {@code Ordered.LOWEST_PRECEDENCE}({@code Integer.MAX_VALUE})다. 어드바이저는 order 오름차순으로
 * 적용되고 값이 작을수록 바깥이므로, {@code MAX_VALUE}보다 작은 값이면 락이 트랜잭션보다 바깥에 와서
 * <b>커밋이 끝난 뒤에</b> 풀린다.
 * <p>
 * 값을 아예 주지 않으면 Aspect도 {@code LOWEST_PRECEDENCE}로 취급되어 트랜잭션 어드바이저와 동점이
 * 되고, 정렬이 안정 정렬이라 둘의 상대 순서가 어드바이저 수집 순서(빈 정의 순서)에 좌우된다. 즉
 * <b>실질적으로 미정의</b>다. 운이 나쁘면 락이 트랜잭션 안으로 들어가는데, 아래에 조건부 UPDATE가
 * 깔려 있으면 결과는 정상으로 나와서 아무도 모른다.
 * <p>
 * <b>아래쪽 경계(AspectJ 바인딩):</b> 그렇다고 {@code HIGHEST_PRECEDENCE}({@code Integer.MIN_VALUE})를
 * 주면 깨진다. {@code @annotation(distributedLock)}으로 애노테이션을 바인딩받으려면 스프링의
 * {@code ExposeInvocationInterceptor}가 먼저 돌아 {@code JoinPointMatch}를 심어줘야 하는데, 그
 * 인터셉터의 order가 {@code PriorityOrdered.HIGHEST_PRECEDENCE + 1}이다. 그보다 앞서면
 * {@code "Required to bind 2 arguments, but only bound 1 (JoinPointMatch was NOT bound in invocation)"}로
 * 터진다.
 * <p>
 * 즉 트랜잭션보다는 바깥, {@code ExposeInvocationInterceptor}보다는 안쪽이어야 한다. 그 사이는
 * 아주 넓으므로 읽기 쉬운 {@code 0}을 쓴다.
 * <p>
 * 순서를 선언으로 보장할 방법은 없다 — {@code @Order}만으로 락을 트랜잭션 <b>안쪽</b>에 둘 수도 없고
 * ({@code MAX_VALUE}보다 큰 값이 없다), 잘못 두면 조용히 넘어간다. 그래서 런타임에 검증한다
 * ({@code isActualTransactionActive()} 가드).
 *
 * @see DistributedLock
 */
@Slf4j
@Aspect
@Component
@Order(0)
@ConditionalOnProperty(prefix = "tikkit.redis", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DistributedLockAspect {

    private static final String KEY_PREFIX = "tikkit:lock:";

    private final RedissonClient redissonClient;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        // 트랜잭션이 이미 열려 있으면 이 락은 커밋 전에 풀린다. 조용히 넘기면 "락을 걸었다"는
        // 사실이 오히려 안심시키므로 바로 터뜨린다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "@DistributedLock이 트랜잭션 안에서 걸렸다. 커밋 전에 락이 풀려 보호가 무효가 된다. "
                            + "Aspect의 @Order를 확인하거나, @Transactional 메서드에서 호출하지 않도록 호출 경로를 바꿔라. "
                            + "대상: " + joinPoint.getSignature().toShortString());
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String key = KEY_PREFIX + resolveKey(distributedLock.key(), signature.getMethod(), joinPoint.getArgs());
        RLock lock = redissonClient.getLock(key);

        boolean acquired = false;
        try {
            acquired = lock.tryLock(
                    distributedLock.waitTime(), distributedLock.leaseTime(), distributedLock.timeUnit());
            if (!acquired) {
                throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED);
            }
            return joinPoint.proceed();
        } finally {
            if (acquired) {
                // 리스가 먼저 만료됐으면 이 스레드는 더 이상 보유자가 아니다.
                // 그대로 unlock()을 부르면 IllegalMonitorStateException이 나서 원래 결과를 덮어쓴다.
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                } else {
                    log.warn("락 리스가 먼저 만료되어 해제를 건너뛴다. 이미 다른 스레드가 들어왔을 수 있다. key={}", key);
                }
            }
        }
    }

    /**
     * SpEL 식을 평가해 락 키를 만든다.
     * <p>
     * {@code #파라미터이름} 참조에는 컴파일 시 {@code -parameters}가 필요하고(Spring Boot Gradle
     * 플러그인이 기본으로 넣는다), record 접근자는 프로퍼티 문법으로도 해석된다. 둘 다 가정이라
     * {@code DistributedLockAspectTest}가 실제로 단정해 둔다.
     * <p>
     * 패키지 전용으로 열어둔 이유는 Redis 없이 키 평가만 단위 테스트하기 위함이다.
     */
    String resolveKey(String expression, Method method, Object[] args) {
        EvaluationContext context =
                new MethodBasedEvaluationContext(null, method, args, parameterNameDiscoverer);
        Object value = expressionParser.parseExpression(expression).getValue(context);
        if (value == null) {
            throw new IllegalStateException(
                    "락 키 SpEL이 null로 평가됐다. 참조한 파라미터가 null이 아닌지 확인하라. 식: " + expression);
        }
        return value.toString();
    }
}
