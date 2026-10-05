# 003. Redis 분산 락 비교 실험과 부분 유니크 인덱스 채택 (Task 020)

| | |
|---|---|
| 관련 Task | Task 020 (Phase 5: 동시성 제어 고도화) |
| 대상 코드 | `ReservationService.create()`, `V3_2__add_pending_hold_unique_index_to_reservations.sql` |
| before 기준선 | [002. DB 락 전략 비교](./002-db-lock-comparison.md) |
| 결론 | **단일 DB에서 분산 락은 불필요**. 중복 선점은 부분 유니크 인덱스로 채택 (`@DistributedLock` AOP는 측정 후 제거) |
| 작성 시점 | 2026-10-05 |

## 1. 배경

[002 문서](./002-db-lock-comparison.md)에서 재고 차감을 조건부 UPDATE로 바꿔 초과 판매를 막았다.
그 문서가 두 가지를 열어둔 채 끝났다.

- **DB 락으로 충분한지 외부 락과 비교해 본 적이 없다.** "예매 동시성에는 Redis 분산 락이 정석"이라는
  통념이 있고, 로드맵상 Redis는 Task 029(캐싱)·031(대기열)에서 어차피 도입한다. 그러면 지금 락까지
  얹는 게 맞는지 수치로 답해야 한다.
- **중복 선점 가드가 동시 요청에 취약하다** (002의 10절). `existsByMemberIdAndTicketGradeIdAndStatus`
  체크와 INSERT 사이가 벌어져 있어 조건부 UPDATE로 묶을 수 없다. 분산 락이 실제로 값을 발휘할 수 있는
  자리이기도 하다.

그래서 두 지점을 모두 측정했다. 축 A는 "이미 DB가 원자적으로 처리하는 경쟁", 축 B는 "DB 한 문장으로
묶이지 않는 경쟁"이다.

## 2. 측정 환경

| 항목 | 값 |
|---|---|
| 축 A 조건 | 재고 10석, 서로 다른 회원 100명, 1매씩 동시 요청 (001·002와 동일) |
| 축 B 조건 | 재고 20석, **같은 회원 1명**, 세션 20개로 동시 요청 |
| DB | PostgreSQL 15 (Testcontainers), READ COMMITTED |
| Redis | 7.4-alpine (Testcontainers `GenericContainer`) |
| HikariCP | `maximum-pool-size=30`, `minimum-idle=30` |
| 동시 진입 | `ExecutorService` + 출발선 래치 3개(`ready`/`start`/`done`) |
| 반복 | 워밍업 1회(20스레드, 결과 폐기) + 3회 측정 |
| 측정 코드 | `ReservationDistributedLockComparisonTest` (채택 후 삭제 — 아래 10절) |

축 B를 20스레드로 낮춘 이유: 같은 회원이라 성공은 어차피 1건이어야 하고, 락 arm은 전원이 한 키에
직렬화되므로 100스레드면 `waitTime`을 넘겨 측정이 "락 획득 실패" 범벅이 된다. 축 A와 조건이 다르므로
두 축의 소요 시간을 서로 비교하면 안 된다.

> [!note]
> 측정 경로에 실험 컨트롤러 위임이 한 단 낀다. 기준선(`PLAIN`)도 같은 컨트롤러를 통과하므로
> arm 간 비교는 공정하지만, **002의 절대 수치와 직접 비교하면 안 된다**
> (002는 실제 `ReservationController`를 탔다).

## 3. Redis 인프라와 `@DistributedLock`

### redisson-spring-boot-starter를 쓰지 않았다

core `org.redisson:redisson:3.50.0`만 넣고 `RedissonClient` 빈을 직접 만들었다. 이유가 둘이다.

1. **스타터 자동설정은 기동 시점에 즉시 연결한다.** `Redisson.create()`가 연결에 실패하면 컨텍스트
   기동 자체가 깨진다. Redis를 띄우지 않는 환경(CI의 `backend` 잡, 평소 로컬 개발)에서 테스트와
   `bootRun`이 전부 막힌다. `spring.autoconfigure.exclude`로 끄고 빈을 직접 만들면 스타터를 쓸 이유가
   없어진다.
2. **`redisson-spring-data-NN` 버전 매트릭스가 따라붙는다.** 접미사가 Spring Boot 버전이 아니라
   Spring Data Redis 라인을 따라가서 수동으로 맞춰야 한다. 우리는 `RLock`만 쓰므로 spring-data-redis
   자체가 필요 없다. Task 029에서 Spring Cache가 필요해지면 그때
   `spring-boot-starter-data-redis`를 정식으로 추가하면 된다.

대신 `tikkit.redis.enabled`(기본 `false`)로 `@ConditionalOnProperty`를 걸었다. 기본값이 꺼짐이라
Redis 없이도 기존 테스트와 dev 기동이 그대로 돈다.

의존성 확인에서 netty가 Redisson이 요구하는 `4.1.121.Final`에서 Boot BOM의 `4.1.119.Final`로
**다운그레이드**됐다. 같은 4.1.x 패치 수준이라 실제 Redis 연결·락 동작에 문제는 없었다. 다만 이걸
파보니 정작 문제는 netty가 아니라 Boot 버전 자체였다 — 3.4 라인의 OSS 보안 패치는 2025-12-31에
끊겼다. 별도 과제(ROADMAP Task 026_1)로 잡았다.

### 애노테이션과 Aspect

```java
// DistributedLock.java (제거됨 — 커밋 5c35680)
String key();                                  // SpEL. 메서드 파라미터를 #이름으로 참조
long waitTime() default 5L;
long leaseTime() default 10L;                  // watchdog(-1)은 쓰지 않는다
TimeUnit timeUnit() default TimeUnit.SECONDS;
```

`leaseTime`에 Redisson watchdog(`-1`)을 쓰지 않았다. 백그라운드 갱신 스레드가 주기적으로 Redis 왕복을
추가해서 지연 측정에 변수가 섞인다.

Aspect는 `tryLock(waitTime, leaseTime, unit)`으로 잡고, `finally`에서 `isHeldByCurrentThread()`를
확인한 뒤 풀었다. 획득 실패는 삼키지 않고 `LOCK_ACQUISITION_FAILED`(409)로 올렸다 — 실패를 삼키면
측정에서 "거절"과 "조용히 통과"가 섞인다.

### 순서는 선언으로 보장할 수 없다

Aspect 진입 시점에 `TransactionSynchronizationManager.isActualTransactionActive()`를 확인해서, 활성
트랜잭션 안이면 `IllegalStateException`을 던지게 했다. 선언만으로는 순서가 보장되지 않기 때문이다
(6절·7절). 검증하는 쪽을 택한 것이 이 설계의 중심이었다.

## 4. 실험 1 — 재고 차감 (축 A)

재고 10석에 서로 다른 회원 100명이 1매씩 동시 요청한다.

| arm | 구성 | 201 | 409 | 락 실패 | 판매 | 총 소요 | 요청 평균 | 요청 최대 |
|---|---|---|---|---|---|---|---|---|
| **PLAIN** | 조건부 UPDATE 단독 (002 채택안) | **10** | **90** | **0** | **10 ✅** | **80~104ms** | **58~72ms** | **79~90ms** |
| LOCK_GRADE | 등급 키 락(트랜잭션 밖) + 조건부 UPDATE | 10 | 90 | 0 | 10 ✅ | 519~673ms | 272~387ms | 508~668ms |
| LOCK_GRADE_IN_TX | 등급 키 락(**커밋 전 해제**) + 조건부 UPDATE | 10 | 90 | 0 | 10 ✅ | 438~480ms | 231~251ms | 429~474ms |

불변식 `remaining_quantity = total_quantity - SUM(활성 예약 수량)`은 세 arm 모두 `0 = 10 - 10`으로 지켜졌다.

### 정확성이 같고 분산 락은 6~7배 느리다

조건부 UPDATE는 `where remaining_quantity >= :quantity` 한 문장이 원자적이라 애플리케이션이 읽은 값을
다시 쓰지 않는다. 그 위에 분산 락을 얹어도 **보탤 정합성이 없다.** 바뀌는 건 요청마다 Redis 왕복이
최소 2회(`tryLock` Lua eval + `unlock` Lua eval + publish) 추가되고, 100개 요청이 키 하나에 줄 선다는
점뿐이다.

### 함정이 수치로 드러나지 않는다

`LOCK_GRADE_IN_TX`는 락을 트랜잭션 **안에서** 잡고 커밋 전에 풀었다. 분산 락 구현에서 가장 흔한
실수인데 **결과는 정확히 10매**다. 아래에 조건부 UPDATE가 깔려 있기 때문이다.

> 분산 락이 제 역할을 하는지 **재고 수치로는 알 수 없다.** 분산 락의 정합성은 자기 자신이 증명하지
> 못하고, 아래 깔린 DB 보장이 증명해준다.

### 함정이 오히려 빠르다

`LOCK_GRADE_IN_TX`(438~480ms)가 `LOCK_GRADE`(519~673ms)보다 빠르다. 커밋 전에 락을 풀면 다음 스레드가
앞 스레드의 커밋과 겹쳐서 돌 수 있다. **정합성을 팔아 처리량을 산 것**이고, 벤치마크만 보면 "더 나은
구현"으로 보인다. 틀린 구현을 성능 근거로 채택하게 되는 경로다.

## 5. 실험 2 — 중복 선점 가드 (축 B)

같은 회원이 같은 등급에 세션 20개로 동시 요청한다. 재고는 20석을 둬서 `SOLD_OUT`이 섞이지 않게 했다.

### before — 인덱스 적용 전

| arm | 구성 | 201 | 409 | PENDING | 총 소요 |
|---|---|---|---|---|---|
| PLAIN | `existsBy...` 가드만 | 20 | 0 | **20 ❌** | 54~56ms |
| LOCK_MEMBER_GRADE | (회원, 등급) 키 락(트랜잭션 밖) | 1 | 19 | **1 ✅** | 84~87ms |
| LOCK_MEMBER_GRADE_IN_TX | 같은 키, **커밋 전 해제** | 1 | 19 | 1 | 75~80ms |
| LOCK_MEMBER_GRADE_IN_TX_DELAYED | 커밋 전 해제 + 커밋 지연 2ms | 2 | 18 | **2 ❌** | 83~89ms |

**20건 전원이 통과했다.** 일부가 아니라 전부인 이유가 중요하다. 재고 차감은 조건부 UPDATE라 같은 행의
락을 두고 직렬화되는데도 그렇다.

```
시각   전원                          A                          B
t0    existsBy(PENDING?) → 없음
      → 가드 통과                                               ← 여기서 이미 결판남
t1                                  재고 UPDATE(락 획득)
t2                                  INSERT
t3                                  커밋 → 락 해제
t4                                                             재고 UPDATE(대기 끝) → INSERT → 커밋
```

가드 통과 여부는 **락을 잡기 전에 이미 결정**돼 있다. 뒤에서 행 락이 직렬화해 줘도 앞에서 내린 판단을
되돌리지 못한다. 락이 "있다"는 사실이 안전을 뜻하지 않는다 — 락이 보호하는 범위 밖에서 읽은 값으로
결정했다면 직렬화는 의미가 없다.

### after — 부분 유니크 인덱스 적용 후

| arm | PENDING (before) | PENDING (after) | 총 소요 (after) |
|---|---|---|---|
| **PLAIN** (락 없음) | **20 ❌** | **1 ✅** | **41~46ms** |
| LOCK_MEMBER_GRADE | 1 ✅ | 1 ✅ | 81ms |
| LOCK_MEMBER_GRADE_IN_TX | 1 | 1 ✅ | 71~73ms |
| LOCK_MEMBER_GRADE_IN_TX_DELAYED | 2 ❌ | **1 ✅** | 79~86ms |

마지막 줄이 결론을 완성한다. **일부러 락을 잘못 건 arm도 인덱스가 들어온 뒤로는 1건이다.** 축 A에서
조건부 UPDATE가 보여준 것과 똑같은 구도다. 그리고 분산 락이 반드시 필요했던 유일한 자리(`PLAIN`)마저
제약으로 메워져서, 이제 락이 하는 일은 지연 2배(41~46ms → 81ms)뿐이다.

## 6. "커밋 전 락 해제" 함정

분산 락을 트랜잭션 안에서 잡고 푸는 것이 왜 위험한가. READ COMMITTED에서 다른 트랜잭션의 **커밋된**
데이터만 보이기 때문이다.

| 락 위치 | 흐름 | 결과 |
|---|---|---|
| 트랜잭션 **밖** (정답) | 락 획득 → tx 시작 → `existsBy` → INSERT → **커밋** → 락 해제 | 다음 스레드가 커밋된 행을 본다 → 1건 성공 |
| 트랜잭션 **안** (함정) | tx 시작 → 락 획득 → `existsBy` → INSERT → **락 해제** → 커밋 | 다음 스레드가 미커밋 INSERT를 못 본다 → 중복 |

### 창이 좁아서 안 터진 것과 구조적으로 안전한 것은 다르다

지연을 넣지 않은 `LOCK_MEMBER_GRADE_IN_TX`는 **1건**이 나왔다. 함정이 그대로 있는데 수치로는
`LOCK_MEMBER_GRADE`와 구분되지 않는다. 경쟁 창이 "unlock 이후 commit 완료까지"로 수백 µs 수준이고,
Redisson의 pub/sub 왕복이 그보다 길어서 다음 스레드가 깨어나기 전에 커밋이 끝나기 때문이다.

그래서 `COMMIT_DELAY_MILLIS = 2`로 창을 결정론적으로 벌린 arm을 따로 뒀더니 **3라운드 모두 정확히
2건**이 나왔다. 2건인 이유도 설명된다 — 1번이 락을 풀고 2ms 자는 사이 2번만 들어오고, 2번이 자는
동안에는 1번이 이미 커밋돼서 3번부터는 409가 된다.

**테스트가 초록이어도 안전해진 게 아니다.** 창이 좁은 버그는 운영의 느린 디스크·긴 트랜잭션에서 터진다.

### 해결: 트랜잭션 밖 + 런타임 검증

`@Order`로 락을 트랜잭션보다 바깥에 두고(7절), 그래도 보장이 안 되므로 Aspect 진입 시점에
`isActualTransactionActive()`로 확인해 터뜨렸다. 선언으로 보장할 수 없는 것은 검증해야 한다.

## 7. 구현에서 걸린 함정

### 함정 1: `@Order`는 위아래 양쪽에 경계가 있다

락을 트랜잭션보다 바깥에 두려고 `@Order(Ordered.HIGHEST_PRECEDENCE)`를 줬더니 깨졌다.

```
IllegalStateException: Required to bind 2 arguments, but only bound 1
                       (JoinPointMatch was NOT bound in invocation)
```

`@annotation(distributedLock)`으로 애노테이션을 바인딩받으려면 스프링의 `ExposeInvocationInterceptor`가
**먼저** 돌아 `JoinPointMatch`를 심어줘야 한다. 그런데 그 인터셉터의 order가
`PriorityOrdered.HIGHEST_PRECEDENCE + 1`(= `Integer.MIN_VALUE + 1`)이다. `HIGHEST_PRECEDENCE`는
`Integer.MIN_VALUE`니까 그보다 앞서고, 바인딩이 성립할 수 없다.

정리하면 경계가 둘이다.

| 경계 | 값 | 넘으면 |
|---|---|---|
| 위 (트랜잭션 어드바이저) | `Ordered.LOWEST_PRECEDENCE` = `Integer.MAX_VALUE` | 락이 트랜잭션 안으로 들어간다 |
| 아래 (`ExposeInvocationInterceptor`) | `HIGHEST_PRECEDENCE + 1` = `Integer.MIN_VALUE + 1` | 애노테이션 바인딩이 깨진다 |

그 사이는 아주 넓어서 읽기 쉬운 `@Order(0)`을 썼다. 블로그 예제가 `HIGHEST_PRECEDENCE`로 안 깨지는
이유는 대개 애노테이션을 바인딩받지 않고 리플렉션으로 꺼내 쓰기 때문이다 — 두 선택이 엮여 있다.

### 함정 2: `@Order`를 안 주면 순서가 미정의다

`@Order`도 `Ordered`도 없는 Aspect는 `AnnotationAwareOrderComparator`가 `LOWEST_PRECEDENCE`로
취급한다. 트랜잭션 어드바이저와 **값이 같아지고**, 정렬이 안정 정렬이라 상대 순서가 어드바이저 수집
순서(빈 정의 순서)에 좌우된다. 즉 실질적으로 미정의다.

운이 나쁘면 락이 트랜잭션 안으로 들어가는데, 4절에서 봤듯 조건부 UPDATE가 깔려 있으면 **결과는 정상으로
나와서 아무도 모른다.** "로컬에서는 되던데"가 여기서 나온다.

### 함정 3: `@Order`만으로 락을 트랜잭션 안쪽에 둘 수 없다

함정을 의도적으로 재현하려 했는데 `MAX_VALUE`보다 큰 order가 없다. 그래서 측정용 함정 arm은 Aspect를
쓰지 않고 `TransactionTemplate` 안에서 `RLock`을 손으로 잡는 러너로 만들었다. Aspect에는 가드가 있어서
애초에 그 상황을 거부하기 때문이다.

### 함정 4: 자기 호출은 Aspect를 아예 건너뛴다

같은 빈 안에서 `this.lockedMethod()`로 부르면 프록시를 거치지 않아 락이 걸리지 않는다. 실험에서
`DistributedLockedHoldFacade`를 `ReservationService`와 별개 빈으로 둔 이유다.

### 함정 5: 조건부 빈은 락을 조용히 무력화한다

Aspect에 `@ConditionalOnProperty`를 걸었더니, Redis가 꺼진 환경에서는 Aspect 빈이 없어서
`@DistributedLock`이 **아무 에러 없이 무효가 된다.** 애노테이션은 그대로 붙어 있고 로그도 없이 보호만
사라진다. 실험 토글로는 편했지만 운영이라면 최악의 실패 모드다 — 켜져 있어야 하는 기능을 "설정으로
끌 수 있게" 만들면 꺼진 걸 눈치챌 방법이 없어진다.

### 함정 6: `src/test`의 `@RestController`는 모든 컨텍스트에 스캔된다

실험 컨트롤러를 `com.tikkit.api.support.lockexperiment`에 뒀더니 실험과 무관한 테스트 59개가 컨텍스트
로딩부터 실패했다. `@SpringBootApplication`이 `com.tikkit.api`에 있어서 컴포넌트 스캔이 그 하위 클래스
패스 전체를 훑고, 거기에 `src/test`도 포함된다. 의존성은 `@TestConfiguration`에만 있으니 어디서나
`UnsatisfiedDependencyException`이 난다.

`@RestController`를 떼고 `@RequestMapping`만 남기는 우회는 통하지 않았다. Spring Framework 6.2의
`RequestMappingHandlerMapping.isHandler()`는 타입에 `@Controller`가 있는지만 보고, `@RequestMapping`만
붙은 타입은 핸들러로 인식하지 않는다. 매핑이 안 잡혀 요청이 정적 리소스 핸들러로 떨어지면서
`NoResourceFoundException`이 1280건 났다.

결국 패키지를 `com.tikkit.lockexperiment`(스캔 범위 밖)로 옮기고 `@Bean`으로 명시 등록했다.

### 틀렸던 가정: record 접근자

계획 단계에서 "SpEL은 record의 `x()` 접근자를 프로퍼티로 보지 못하니 `#request.ticketGradeId()`처럼
메서드 호출 문법을 써야 한다"고 적어뒀는데, 실제로는 **두 문법 다 통한다.** 스프링의
`ReflectivePropertyAccessor`가 record 접근자를 인식한다. 키 평가를
`resolveKey(String, Method, Object[])` 순수 메서드로 떼어내 Redis 없이 단위 테스트한 덕에 2초 만에
확인됐다. Aspect 안에 묻어뒀으면 이 문서에 틀린 항목이 하나 들어갔을 것이다.

## 8. 결론 — 단일 DB에서 분산 락은 불필요

| 기준 | 조건부 UPDATE / DB 제약 | Redis 분산 락 |
|---|---|---|
| 재고 경쟁 정확성 | 10매 ✅ | 10매 ✅ (차이 없음) |
| 중복 선점 정확성 | 1건 ✅ (인덱스) | 1건 ✅ |
| 축 A 총 소요 | **80~104ms** | 519~673ms (6~7배) |
| 축 B 총 소요 | **41~46ms** | 81ms (2배) |
| 잘못 썼을 때 | 조건이 WHERE에 있어 틀리면 바로 드러난다 | **결과가 정상으로 나와 드러나지 않는다** (4절) |
| 필요한 전역 규약 | 없음 (경로와 무관하게 성립) | **있음** — 모든 쓰기 경로가 같은 키로 락을 잡아야 한다 |
| 운영 구성요소 | DB뿐 | Redis 추가 (SPOF, 장애 시 예매 전면 중단) |
| 추가 스키마 | 부분 유니크 인덱스 1개 | 없음 |

결정적 근거는 성능이 아니라 **구조**다. 분산 락은 "모든 쓰기 경로가 같은 키로 락을 잡아야 한다"는
전역 규약을 요구하고 아무것도 강제하지 않는다. 만료 배치나 관리자 경로가 그 규약을 어기면 조용히
뚫린다. 002에서 낙관적 락을 기각한 논리와 같다 — 그쪽은 "모든 쓰기가 version을 올려야 한다"였고,
실제로 만료 배치의 네이티브 CTE가 그걸 어기고 있었다.

반면 DB 제약과 조건부 UPDATE는 **어느 경로로 들어와도 성립한다.** 새로 합류한 사람이 규약을 몰라도
깨지지 않는다.

> 분산 락은 DB가 이미 가진 보장을 네트워크 왕복으로 재구현한 것이다. 여러 DB에 걸친 작업이나 DB 밖의
> 자원(외부 API 호출 횟수, 파일)을 보호할 때는 다른 선택지가 없지만, 단일 DB의 한 테이블을 두고 다투는
> 경쟁에는 보탤 것이 없다.

## 9. 중복 선점은 부분 유니크 인덱스로 채택

```sql
-- V3_2__add_pending_hold_unique_index_to_reservations.sql
CREATE UNIQUE INDEX uk_reservations_pending_member_grade
    ON reservations (member_id, ticket_grade_id)
    WHERE status = 'PENDING';
```

`WHERE` 절이 붙은 부분 인덱스는 테이블 `CONSTRAINT`로 선언할 수 없어서 `uk_` 접두어를 유지한
`CREATE UNIQUE INDEX`로 만들었다. `CANCELLED`/`EXPIRED`는 재고를 반납한 상태라 제한 대상이 아니고,
`CONFIRMED`는 여러 건을 가질 수 있어야 하므로 `PENDING`만 조건에 넣었다.

**번호를 `V3_2`로 쓴 이유**: `docs/ERD.md`에 V4~V8이 미래 계획으로 잡혀 있어 `V4`를 쓰면 ERD와
ROADMAP 참조가 밀린다. Flyway는 `V3_2`를 3.2로 해석해 3.1과 4 사이에 끼운다 (002의 `V3_1`과 같은 판단).

**`@Table(uniqueConstraints=...)`를 쓰지 않았다**: Hibernate가 부분 조건을 표현할 수 없어 오히려 틀린
정보가 된다. `ddl-auto: validate`는 인덱스를 검증하지 않으므로 엔티티는 건드리지 않았다.

### 409 변환을 서비스에 둔 이유

```java
// ReservationService.java — save()를 감싼다
catch (DataIntegrityViolationException e) {
    if (isDuplicatePendingHold(e)) {
        throw new BusinessException(ErrorCode.DUPLICATE_PENDING_RESERVATION);
    }
    throw e;
}
```

`GlobalExceptionHandler`에서 처리하면 **어떤 제약인지 알 수 없어** 예약번호 시퀀스 한 바퀴
(`uk_reservations_reservation_no`)나 동시 이중 결제(`uk_payments_reservation_id`)까지 모두 409로
뭉개게 된다. 그쪽은 의미가 다른 별개 과제다(10절).

제약명은 원인 체인의 Hibernate `ConstraintViolationException#getConstraintName()`에
`uk_reservations_pending_member_grade`가 그대로 담겼다. 레포지토리 테스트로 실제 값을 찍어 확인하고
그 단정을 테스트에 박아뒀다 — DB·드라이버·Hibernate가 바뀌면 잡힌다.

**이게 가능한 건 `Reservation`의 PK가 `IDENTITY`이기 때문이다.** `save()` 시점에 INSERT가 나가므로
예외가 서비스 메서드 안에서 잡힌다. `SEQUENCE`였다면 커밋 시점에 터져서 서비스에서 못 잡고 500으로
떨어졌을 것이고, 그러면 `GlobalExceptionHandler`에서 제약을 구분할 수 없는 문제로 돌아간다.

### `existsBy` 가드는 남겼다

인덱스만 믿으면 모든 중복 요청이 재고 UPDATE까지 갔다가 롤백된다. `existsBy`는 빠른 경로, 인덱스는
최종 방어선으로 역할이 나뉜다. 덤으로 새 인덱스가 `existsBy` 쿼리를 그대로 커버한다 — 전에는
`idx_reservations_member_created_at`의 선두 컬럼만 쓰고 있었다.

## 10. 버린 것과 남긴 것

### 버린 것

| 버린 것 | 이유 |
|---|---|
| `common/aop/DistributedLock`, `DistributedLockAspect` | 붙은 곳이 없다. 조건부 빈이라 "켜면 동작할 것처럼 보이지만 아무 데도 안 붙은" 상태로 남는 게 더 위험하다 (함정 5) |
| `ErrorCode.LOCK_ACQUISITION_FAILED` | 발생 경로 소멸. 실험 코드라 `docs/PRD.md` 부록 B에는 애초에 넣지 않았다 |
| `spring-boot-starter-aop` | 커스텀 Aspect가 다시 0개가 된다 |
| `com.tikkit.lockexperiment` 전체 (5파일) | 측정 전용 |
| `AbstractRedisConcurrencyTest`, `ReservationDistributedLockComparisonTest` | 측정 전용 |

### 남긴 것

| 남긴 것 | 이유 |
|---|---|
| docker-compose의 `redis`, `.env.example`의 `REDIS_PORT` | Task 029(캐싱)·031(대기열)에서 다시 쓴다 |
| `org.redisson:redisson:3.50.0`, `application.yml`의 `tikkit.redis.*`, `RedissonConfig` | 같음. 기본값이 `false`라 CI와 평소 dev는 Redis를 요구하지 않는다 |
| `V3_2` + 서비스의 409 변환 | **이번 Task의 유일한 영구 채택** |
| `ReservationConcurrencyTest.중복_선점_차단()` | 영구 회귀 테스트 (201 1건 / 409 19건 / PENDING 1건 / 잔여 19) |

실험 코드를 `src/test`에 둔 것은 002와 다른 선택이었다. 002는 전략을 런타임에 갈아끼워야 해서 main에
뒀고, 그래서 정리 커밋에서 운영 코드 여러 곳을 동시에 건드려야 했다. 이번엔 정리 커밋이 파일 삭제와
애노테이션 2개 제거로 끝났다(`17 insertions, 931 deletions`). 대가는 함정 6이었다.

## 11. 남은 한계

- **결론은 "단일 DB, 단일 인스턴스" 전제에서만 유효하다.** 애플리케이션을 여러 대로 늘려도 DB가 하나면
  결론은 그대로다(제약과 조건부 UPDATE는 DB에서 성립한다). 하지만 DB를 샤딩하거나 DB 밖의 자원(외부 API
  호출 횟수, 파일, 서드파티 쿼터)을 보호해야 하면 분산 락 말고는 선택지가 없다. Task 026(다중 인스턴스
  세션)에서 "상태를 어디에 두는가"를 다시 다룬다.
- **Redis 오버헤드는 하한으로 읽어야 한다.** Testcontainers Redis는 같은 호스트의 루프백이다(왕복
  0.1~0.3ms 수준). 운영은 같은 VPC의 별 인스턴스라 0.5~2ms로 늘어난다. 가장 유리한 조건에서도
  조건부 UPDATE보다 6~7배 느렸으므로 결론의 방향은 바뀌지 않는다.
- **Redlock 논쟁은 다루지 않았다.** 단일 Redis 인스턴스를 썼으므로 "Redis 복제 지연 중 마스터 장애 시
  락이 둘에게 주어질 수 있는가"는 측정 범위 밖이다. 애초에 락을 채택하지 않았으므로 과제로 남기지 않는다.
- **`DataIntegrityViolationException`은 여전히 500이다.** 중복 선점 위반만 골라 409로 바꿨고,
  `uk_reservations_reservation_no`·`uk_payments_reservation_id` 위반은 그대로다. 002의 10절에서
  후속 과제로 남긴 것이 그대로 남아 있다.
- **인덱스는 `PENDING`만 막는다.** 같은 회원이 같은 등급에 `CONFIRMED` 예약을 여러 건 갖는 것은
  의도된 동작이다(여러 번 나눠 결제). 그걸 제한해야 하는 정책이 생기면 조건을 다시 봐야 한다.
- **측정은 로컬 Docker 기준이다.** 절대 수치는 환경에 따라 달라지므로 **방식 간 상대 비교**로만 읽어야
  한다. CI 러너에서는 분포가 다를 수 있다.

## 12. 다음 단계

Phase 5가 끝난다. `v0.2.0-concurrency` 태그를 만든다.

[Phase 6: 지정석 전환](../ROADMAP.md) — Task 021~022에서 좌석 단위로 바뀌면 "여러 좌석을 한 번에
선점"이 되고, 이건 축 A보다 어려운 경쟁이다. 정렬된 좌석 ID 기준 다중행 조건부 UPDATE로 데드락 없이
처리할 계획이다. 이번 결론대로 분산 락은 쓰지 않는다.

Redis는 Task 029(Spring Cache 캐싱)와 Task 031(ZSET 대기열)에서 다시 등장한다. 그때는 락이 아니라
**원래 Redis가 잘하는 일**(TTL 캐시, 정렬 집합)로 쓴다.

## 재현 방법

```bash
cd tikkit-back
docker ps                   # Docker 데몬이 떠 있어야 한다 (Testcontainers)
./gradlew test --tests '*ReservationConcurrencyTest*'
```

수치는 테스트 로그에 `[초과 판매 차단]`, `[결제-만료 경쟁 차단]`, `[중복 선점 차단]`으로 출력된다.

분산 락 비교 측정은 채택 후 삭제했다. 재현하려면 측정 커밋을 꺼내야 한다.

```bash
# 축 A·B 전체 측정 (인덱스 적용 전)
git show be81103

# 인덱스 적용 후 축 B 재측정
git show a2f724f

# 측정 코드 자체를 되살려 돌리고 싶으면
git checkout be81103 -- tikkit-back/src/test/java/com/tikkit/lockexperiment \
    tikkit-back/src/test/java/com/tikkit/api/support/AbstractRedisConcurrencyTest.java \
    tikkit-back/src/test/java/com/tikkit/api/integration/reservation/ReservationDistributedLockComparisonTest.java \
    tikkit-back/src/main/java/com/tikkit/api/common/aop
# build.gradle에 spring-boot-starter-aop와 ErrorCode.LOCK_ACQUISITION_FAILED도 함께 되살려야 한다
```
