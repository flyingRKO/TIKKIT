# 008. 캐싱 적용 — 캐시가 줄일 수 없는 것을 먼저 찾았다 (Task 029)

| 항목 | 내용 |
|---|---|
| 관련 Task | Task 029 (Phase 8: 성능 개선) |
| 대상 코드 | `CacheConfig`·`CacheNames`·`CacheProperties`·`RedisConnectionConfig`(신규), `CatalogCache`·`SeatmapResponseCache`(신규), `ScheduleService`·`PerformanceService`·`ReservationExpiryScheduler`, `Cache*Test` 5종(신규) |
| 작성 시점 | 2026-10-17 |
| before 기준선 | [007. 쿼리·인덱스 최적화](007-query-optimization.md) 7.1절 — 탐색 p95 2.48ms / 좌석맵 6.77ms / 예매 7.77ms. **같은 세션에서 다시 쟀다**(A군) |
| 결론 | *(측정 후 채움)* |
| 알게 된 것 | **서비스 레이어 `@Cacheable`은 직렬화 비용을 줄일 수 없다.** 캐시가 돌려주는 건 객체 리스트고, 컨트롤러가 반환하면 Jackson이 237.9KB를 다시 쓴다. 007이 지목한 이득의 출처를 캐시로 잡으려면 응답 바이트를 캐시해야 하고, 그러면 좌석 상태가 stale해진다 |
| 데이터 | `schedule_seats` 6,026,894행, 공연 1,008 / 회차 3,020 / 회원 503, `reservations` 55,429행(006 측정 당시 13,798 → 007 약 43,000 → 지금) |

---

## 1. 왜 만들었나

ROADMAP의 Task 029는 "Spring Cache + Redis로 공연 목록/상세 캐싱"이다. 그런데 앞선 두 Task가
이 Task로 넘긴 숙제가 그 한 줄보다 까다로웠다.

> 캐시 적용 후에는 **적중률을 따로 재야** p95 개선이 캐시 덕인지 확인할 수 있다.
> — [006](006-performance-baseline.md) 10절

> 4.5·4.6절이 보여준 건 **이 규모에서 DB가 병목이 아니라는 것**이다. 공연 목록이 0.55ms인데
> 캐시를 붙여도 p95에서 보일 여지가 작다. (…) **캐시의 이득은 DB 시간이 아니라 좌석맵
> 237.9KB의 직렬화 비용에서 나올 가능성이 크다. 무엇이 줄어드는지 먼저 정하고 재야 한다.**
> — [007](007-query-optimization.md) 10절

그리고 ROADMAP에는 이렇게도 적혀 있었다.

> 좌석·잔여 수량은 캐싱 대상에서 제외하고 이유를 문서화

**둘이 충돌한다.** 007이 지목한 이득의 출처가 ROADMAP이 제외하라는 바로 그 응답이다.
그래서 이 Task의 목표를 "캐시를 붙인다"가 아니라 **"캐시가 무엇을 줄이는지 수치로 분해하고,
줄일 수 있는 것만 채택한다"**로 잡았다. Task 022와 028이 인덱스를 하나도 추가하지 않고
끝났듯이, 일부가 기각으로 끝나는 것도 결과로 받는다.

### 1.1 이 서비스의 캐시 무효화는 왜 쉬운가

착수하고 코드를 훑으면서 evict 설계를 통째로 단순하게 만드는 사실 두 개를 확인했다.

**공연 데이터를 바꾸는 쓰기 API가 없다.** 전체 코드베이스의 POST/PUT/PATCH/DELETE 매핑은
4개뿐이고 전부 auth와 reservation이다. `Performance`·`Schedule`·`TicketGrade`·`Seat`·`Venue`
엔티티에는 setter도 update 메서드도 0개다. 관리자 API는 없고 `MemberRole.ADMIN`은 enum 값만
있다. 공연 데이터는 Flyway 시드와 부하 테스트 시더로만 들어온다.

**그래서 무효화 지점이 하나다.** `ReservationExpiryScheduler`가 60초마다 돌리는
`PerformanceRepository.recalculateDerivedFields()`가 `performances.status`·`start_date`·`end_date`를
재계산하고, 이 세 컬럼이 전부 목록·상세 응답에 들어 있다. 그 메서드는 `IS DISTINCT FROM`으로
**실제로 값이 바뀐 행 수를 반환**하므로, 그 값이 0보다 클 때만 캐시를 비우면 된다.

일반적인 캐싱 작업에서 제일 성가신 부분(어느 쓰기가 어느 캐시를 더럽히나)이 이 서비스에는
거의 없다. 대신 성가신 쪽은 다른 데 있었다 — 6절.

## 2. 측정 환경

007 7.1과 같은 조건으로 맞췄다. 조건이 하나라도 다르면 before/after를 비교할 수 없다.

- `docker-compose.prod.yml` 전체 스택(nginx + BE 2대 + postgres + redis), `--profile monitoring`
- nginx keepalive ON (006 4.6에서 기준선으로 고정한 상태)
- `schedule_seats` 6,026,894행, 부하 데이터 공연 1,000 / 회차 3,000
- k6는 compose 서비스로, `--no-deps`로 띄워 BE 재생성을 막는다
- 측정 사이마다 `load-test/sql/98-reset-reservations.sql`
- 각 군 3회, **중앙값**

### 2.1 측정 군

캐시의 효과만 보려면 "캐시 켜고 끄기" 두 군으로는 부족하다. 좌석맵 쿼리를 둘로 쪼갠 변경이
캐시와 무관하게 비용을 만들기 때문에, 코드 변경과 캐시 효과를 분리해야 한다.

| 군 | 코드 | `CACHE_TYPE` | 라벨 |
|---|---|---|---|
| A | `main` (쿼리 분리 전) | — | `t029-a-main` |
| A′ | 이 브랜치 | `none` | `t029-b-none` |
| B | 이 브랜치 | `caffeine` | `t029-c-caffeine` |
| C | 이 브랜치 | `redis` | `t029-d-redis` |
| D | 이 브랜치 | `redis` + 응답 바이트 캐시 | `t029-e-bytes` |

읽는 방법은 이렇다.

- **A vs A′** — 좌석맵 쿼리 분리의 비용 (캐시가 없을 때)
- **A′ vs C** — 캐시의 순이득
- **B vs C** — 로컬 캐시 vs 공유 캐시
- **C vs D** — 직렬화 비용의 상한
- **C의 균일 분포 vs 편중 분포** — 트래픽 분포 민감도

A를 007 수치로 대신하지 않고 다시 쟀다. 007이 "before/after 조건이 어긋나 있다"를 한계로
남겼고(그 사이 `reservations`가 계속 쌓였다), 지금은 55,429행으로 더 늘었다. 같은 세션에서
다시 재지 않으면 이번 비교도 같은 흠을 갖는다.

### 2.2 관문 — 워밍업 런이 없으면 조건이 안 맞는다

처음 A군을 쟀을 때 3회가 **10.25 / 7.63 / 7.41ms**로 흩어졌다. 006·007의 산포가 ±0.2ms였으니
조건이 다른 것이다.

원인은 **컨테이너 교체 직후의 차가운 JVM**이었다. 이 Task는 군마다 `CACHE_TYPE`을 바꿔
backend를 재생성해야 해서, 006·007과 달리 매번 JVM이 처음부터 시작한다. 006의 재현 방법이
`--no-deps`를 강조한 이유가 바로 "BE를 재생성하지 말라"는 것인데, 이 Task는 재생성을 피할 수
없다.

그래서 시나리오마다 **20초짜리 워밍업 런을 먼저 돌리고 버린다**. 같은 A군이
**6.79 / 6.63 / 6.31ms**로 수렴했고 007의 6.77ms와 겹친다. 워밍업을 넣지 않았으면 캐시를
켠 군이 "빨라졌다"고 나왔을 것이다 — **실제로는 두 번째 측정이라서 빨라진 것**이다.

## 3. 무엇을 캐시했고 무엇을 뺐나

응답을 "변하는 것"과 "변하지 않는 것"으로 가른 다음 변하지 않는 쪽만 캐시했다.

| 캐시 | 키 | 값 | TTL | evict |
|---|---|---|---|---|
| `performanceList` | `category\|status\|page\|size` | `PerformanceListPage` | 60s | 배치(변경 행 > 0) |
| `performanceDetail` | 공연 id | `PerformanceDetailResponse` | 60s | 배치(변경 행 > 0) |
| `gradePrices` | 회차 id | `List<TicketGradeRow>` (가격·등급) | 10m | 없음 |
| `seatLayout` | 회차 id | `List<ScheduleSeatLayoutRow>` (좌표·등급) | 10m | 없음 |

**제외한 것과 이유**

- **잔여 수량**(`remainingQuantity`) — `schedule_seats`의 AVAILABLE COUNT다. 예매마다 바뀌고
  FE의 매진 판정(`remainingQuantity === 0`)이 여기 걸려 있다. 가격만 캐시하고 COUNT는 매번
  센다. 둘을 한 덩어리로 캐시하면 매진된 회차가 TTL 동안 "잔여 있음"으로 보인다.
- **좌석 상태**(`ScheduleSeatResponse.status`) — 선점·확정·취소·만료 네 경로가 바꾼다.
  좌석맵 8필드 중 **7개가 불변**이고 변하는 건 이것 하나다. 그래서 배치도만 캐시하고 상태는
  매번 조회해 서비스에서 합친다. 응답 형식은 그대로라 FE 계약이 바뀌지 않는다.
- **keyword 검색** — 사용자가 아무 문자열이나 넣을 수 있어서 키 공간이 무한하다.
  `@Cacheable(condition = "#keyword == null || #keyword.isBlank()")`로 통과시킨다.
- **없는 회차의 빈 결과** — 키가 경로 변수라 **사용자가 키 공간을 고를 수 있다**. 무작위 id를
  쏘는 것만으로 TTL 10분짜리 빈 항목이 쌓인다. `unless = "#result.isEmpty()"`로 막았다.

ROADMAP의 "좌석·잔여 수량은 캐싱 대상에서 제외"는 **"응답 전체를 캐시하지 않는다"로
해석했다.** 좌석 좌표는 공연장에 고정이고 회차-좌석 매핑도 생성 시 정해지므로, 그걸 캐시하는
것은 "좌석 재고를 캐시한다"와 다른 일이다. 재고에 해당하는 값(status, 잔여 수량)은 그대로
매번 읽는다.

### 3.1 캐시 전용 빈을 따로 둔 이유

`@Cacheable`은 스프링 프록시를 거쳐야 동작한다. 같은 클래스 안에서 호출하면 프록시를 타지 않고
**예외도 경고도 없이 캐시가 동작하지 않는다**(적중률만 영구히 0%다). `ScheduleService.getSeats()`는
캐시된 배치도와 실시간 상태를 합쳐야 하므로 "캐시되는 조회"와 "그걸 쓰는 조립"이 같은 메서드에
있을 수 없다. 그래서 캐시 대상만 `CatalogCache`로 뺐다.

공연 목록·상세는 이 문제가 없어서 `PerformanceService`에 직접 붙였다 — 컨트롤러가 바깥에서
부르므로 프록시를 탄다. 대신 목록은 반환형을 바꿔야 했다(5.2).

### 3.2 `@EnableCaching(order = HIGHEST_PRECEDENCE)`

캐시 어드바이저와 트랜잭션 어드바이저의 기본 order는 **둘 다** `Ordered.LOWEST_PRECEDENCE`라서
어느 쪽이 바깥인지 보장되지 않는다. 트랜잭션이 바깥이면 캐시 히트에도 커넥션을 잡고 트랜잭션을
열었다 닫는다. 캐시를 가장 바깥에 둬서 히트 경로가 Hikari를 아예 거치지 않게 했다.

## 4. 핵심 발견

*(측정 결과를 반영해 채운다)*

## 5. 실제로 만든 것

### 5.1 토글 — `tikkit.cache.type`

`none` | `caffeine` | `redis`, 기본 `none`. `tikkit.auth.session-store`와 같은 모양으로 맞췄다.

기본값을 `none`으로 둔 이유는 두 가지다. 하나는 "Redis 없이도 기동·테스트가 돌아야 한다"는
기존 결정([003](003-redis-distributed-lock.md) 3절)을 지키는 것 — 통합 테스트가 캐시를 모르는
상태로 그대로 돈다. 다른 하나는 **캐시가 채택을 판정하는 대상**이라는 것이다. 스택을 띄우는
것만으로 켜지면 "캐시 없는 기준선"을 다시 재기 어렵다.

`none`은 `NoOpCacheManager`라서 `@Cacheable`이 전부 통과만 한다. 조건부 빈으로 어노테이션을
지우지 않고도 캐시가 완전히 꺼진다.

### 5.2 `Page`를 캐시할 수 없어서 반환형을 바꿨다

`PageImpl`은 기본 생성자가 없고 `Pageable`을 품고 있어서 Jackson으로 역직렬화할 수 없다.
직렬화는 되지만 꺼낼 때 깨진다.

우회 방법이 둘이었다. mixin이나 커스텀 역직렬화기를 붙이는 쪽은 Spring Data의 내부 구조에
의존하게 되고 버전이 올라갈 때 조용히 깨진다. 반대로 서비스가 캐시 친화적인 타입을 돌려주고
컨트롤러가 응답으로 조립하면 캐시는 평범한 record 하나만 알면 된다. 후자를 골랐다 —
`PerformanceListPage(List<PerformanceSummaryResponse> content, long totalElements)`.

덤으로 3.1의 self-invocation 함정을 피한다. `list()` 자체에 `@Cacheable`을 붙일 수 있어서
캐시 전용 메서드를 따로 둘 필요가 없다. `PageResponse`에는 `Page` 없이 조립하는 생성자를
추가했고, 기존 `PageResponse(Page<T>)`는 예약 목록이 쓰므로 남겼다.

### 5.3 연결 팩토리를 세션과 공유한다

Task 026은 `RedisConnectionFactory`를 `tikkit.auth.session-store=redis`일 때만 만들었다.
캐시가 들어오면서 조건이 하나 더 필요해졌다 — 로컬 개발에서 `session-store=memory` +
`cache.type=redis` 조합을 써야 한다(세션까지 Redis로 올리면 `repository-type: indexed`가
기동 시점에 `CONFIG SET`을 쏘므로 통합 테스트가 Redis를 기다린다).

`@ConditionalOnProperty`는 여러 개를 붙이면 AND로 묶이므로 `AnyNestedCondition`으로 OR를
만들었다. `SessionRedisConfig`는 `RedisConnectionConfig`로 합치고 Javadoc을 옮겼다.

`application.yml`의 `DataRedisAutoConfiguration` 제외는 **그대로 뒀다.** 제외를 풀면 프로퍼티
없이도 localhost:6379 팩토리가 생겨서 토글이 무력해진다.

### 5.4 Caffeine은 `SimpleCacheManager`로 조립했다

`CaffeineCacheManager`는 매니저 단위로 spec 하나만 받는다. 목록(60초)과 좌석 배치(10분)의 TTL이
다르므로 캐시를 개별로 만들어 `SimpleCacheManager`에 담았다. 로컬 캐시는 힙을 먹으므로
`maximumSize(2_000)`로 상한을 뒀다 — Redis 군의 `maxmemory`와 같은 역할이다.

### 5.5 기각한 것

*(측정 결과를 반영해 채운다)*

## 6. 관문 — 캐시는 조용히 틀린다

이 Task에서 가장 많은 시간을 쓴 곳은 설계가 아니라 **"동작하는 것처럼 보이는데 아닌" 상태**를
찾아내는 일이었다. 네 개가 전부 예외도 경고도 없이 지나갔다.

### 6.1 TTL이 전혀 걸려 있지 않았다

`RedisCacheManager.builder()`를 이렇게 짰다.

```java
.cacheDefaults(base)
.withInitialCacheConfigurations(perCache)   // 이름별 TTL
.initialCacheNames(perCache.keySet())       // 미리 만들기
```

설정 코드만 읽으면 멀쩡하다. 그런데 Redis에서 `PTTL`을 찍으면 **네 캐시가 전부 `-1`(만료
없음)** 이었다. `initialCacheNames(names)`는 주어진 이름을 **현재 `cacheDefaults`로** 미리
만들기 때문에, 앞 줄에서 준 캐시별 TTL을 그대로 덮는다.

`withInitialCacheConfigurations`만으로도 그 이름들이 기동 시점에 만들어지므로
`initialCacheNames`를 지우면 끝이다. 문제는 그게 아니라 **확인하지 않으면 모른다**는 것이다.
이걸 못 잡았으면 "목록·상세는 TTL 60초"라고 이 문서에 적어두고 영구 캐시를 운영했을 것이다.
evict가 실패했을 때의 안전망이 없는 상태인데 없는 줄 모르는 쪽이 더 나쁘다.
`CacheTtlTest`가 설정이 아니라 **Redis 키의 남은 수명**을 직접 읽어 고정한다.

### 6.2 캐시 키에 SpEL의 `null` 문자열화를 기대했다

```java
key = "#category + '|' + #status + '|' + #page + '|' + #size"
```

`category`가 `null`일 때 `"null|ON_SALE|0|20"`이 될 거라고 생각했는데 아니었다. 캐시는
동작하는데 내가 기대한 키가 아니라서, 키를 직접 꺼내 보는 테스트가 깨졌다. Elvis로 `'-'`를
명시했다 — `(#category ?: '-')`. `'-'`는 enum 값에 나올 수 없는 문자라 `category=null`과
특정 카테고리가 같은 키로 뭉칠 수 없다.

### 6.3 타입 검증기가 `BigDecimal`을 막았다

`GenericJacksonJsonRedisSerializer`에 default typing을 켜려면
`PolymorphicTypeValidator`가 필요하다. `enableUnsafeDefaultTyping()`은 JSON에 적힌 어떤 클래스든
역직렬화하므로 쓰지 않았고(Redis에 쓰기 권한을 가진 쪽이 임의 클래스를 올리는 고전적인 gadget
경로다), 허용 목록을 우리 패키지와 `java.util.`로 좁혔다.

그러자 등급 가격 조회가 터졌다.

```
Could not resolve type id 'java.math.BigDecimal' as a subtype of java.math.BigDecimal:
Configured PolymorphicTypeValidator denied resolution
```

**타입 id는 final이 아닌 모든 값에 붙는다.** 우리 DTO만 허용하면 `BigDecimal`(가격)과
`Instant`·`LocalDate`(회차 시각, 공연 기간)에서 막힌다. `java.math.`와 `java.time.`을 더했다.

### 6.4 `clear()`와 `FLUSHDB`가 즉시 반영되지 않는다

테스트가 실행마다 다른 조합으로 깨졌다. 증상은 "캐시에 넣었는데 꺼내면 없다"였고 직렬화
문제로 한참 오진했다. 쓰기를 1,000회 재시도 루프로 재 보니 **put은 0~1회 안에 보인다** —
쓰기는 멀쩡했다.

범인은 **삭제가 늦게 도착하는 것**이었다. 키를 덤프해서 시간 순으로 비교하니, 앞 테스트의
`@BeforeEach`가 쏜 `FLUSHDB`가 다음 테스트가 캐시를 채운 **뒤에** 처리되면서 방금 넣은 값을
지우고 있었다. `Cache.clear()`도 같다 — 실측하면 clear 직후의 `get`이 값을 그대로 돌려주고
그 다음 호출에서야 사라진다.

테스트는 두 가지로 고쳤다. 하나는 **키 공간을 테스트마다 분리**하는 것 — 근본 원인이
`truncateAll()`의 `RESTART IDENTITY`라서 공연·회차 id가 매번 1부터 다시 시작하고 캐시 키가
재사용된다. id 구간을 1,000씩 밀면 늦게 도착한 삭제가 지난 테스트의 키만 건드린다. 다른 하나는
`clear()`를 트리거한 테스트가 **자기 안에서 완료를 기다리는** 것이다(`awaitCacheMiss`) —
`clear()`는 키 하나가 아니라 캐시 이름 전체를 패턴으로 지우므로 키 분리로는 막을 수 없다.

> [!warning] 확인 필요
> 왜 void를 반환하는 명령만 늦는지는 규명하지 못했다. 같은 커넥션에서 응답을 읽는 명령
> (`DBSIZE`)을 뒤에 붙여 순서를 강제해도 재현됐다. 운영에서는 문제가 되지 않는다 — evict를
> 거는 배치가 60초 주기이고 지연은 ms 단위다. 최악의 경우 evict 직후 채워진 값이 뒤늦은 삭제에
> 날아가는데 그건 **캐시 미스 한 번일 뿐 틀린 응답이 아니다.**

### 6.5 범위 밖: ShedLock이 테스트를 조용히 건너뛴다

evict 테스트에서 배치를 두 번 호출했는데 두 번째가 아무 일도 하지 않았다.
`@SchedulerLock(lockAtLeastFor = "55s")`가 정상 종료 후에도 55초간 락을 들고 있고,
`AbstractConcurrencyTest.truncateAll()`의 테이블 목록에 `shedlock`이 없어서 그 행이 남는다.
증상이 "evict가 안 됐다"로 나타나 캐시 문제로 오진하기 쉽다. 테스트에서 `DELETE FROM shedlock`을
넣어 해결했다.

## 7. 수치

*(측정 후 채움)*

## 8. 메모리 — 캐시와 세션이 한 Redis를 쓴다

`seatLayout` 항목은 좌석당 약 180바이트다(JSON + `@class` 타입 정보). 2,000석 회차 하나가
약 360KB이고, 부하 데이터의 회차 3,000개를 TTL 10분 안에 다 훑으면 **약 1GB**가 된다.
측정에서는 표본이 60회차라 그보다 훨씬 작지만(7절), 운영 외삽값은 1GB다.

상한이 없으면 Redis가 호스트 메모리를 먹다가 OOM으로 죽고, 그러면 **세션까지 같이 날아가
전 사용자가 로그아웃된다**([005](005-session-scaling.md) 9절이 남긴 "단일 Redis라 Redis가
죽으면 전 사용자가 로그아웃된다"가 그대로 커진다). 그래서 `maxmemory`와
`maxmemory-policy allkeys-lru`를 걸었다.

대가는 분명하다 — **세션 키도 축출 대상이다.** 캐시가 상한을 채우면 로그인이 임의로 풀릴 수
있다. `volatile-lru`도 다르지 않다(세션에도 TTL이 있다). `noeviction`은 더 나쁘다 — 가득 차면
캐시 쓰기가 실패해 요청이 500으로 떨어진다.

**캐시 전용 Redis를 따로 띄우는 쪽은 생각보다 비싸다.** `RedisConnectionFactory` 빈이 둘이
되면 Spring Session 자동설정의 `@ConditionalOnBean(RedisConnectionFactory.class)`가 캐시 쪽
팩토리에도 걸려서, **"캐시만 Redis로 켠다"가 세션까지 켜버린다.** Task 026이 만든 "프로퍼티
하나로 세션 저장소 전체가 켜지고 꺼진다"는 구조가 깨진다. 논리 DB를 나눠도 해결되지 않는다 —
축출은 인스턴스 단위다. 지금 규모에서는 단일 인스턴스 + 상한이 낫다고 보고 분리는 다음 단계로
넘겼다.

## 9. 한계

*(측정 후 채움)*

## 10. 다음 단계

*(측정 후 채움)*

---

## 재현 방법

```bash
# 0. Git Bash라면 경로 번역을 꺼야 한다 (007 6절). PowerShell이면 생략.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# 1. 테스트 — 캐시 off 기준값과 Redis 컨테이너를 띄우는 캐시 테스트가 같이 돈다
cd tikkit-back && ./gradlew test
#   *QueryCountTest       : 캐시 off의 문장 수 (목록 1/2, 상세 2, 등급 2, 좌석맵 2)
#   CacheQueryCountTest   : Redis 캐시의 미스/히트 문장 수 + 적중률 지표
#   CacheTtlTest          : PTTL을 직접 읽어 캐시별 TTL 확인 (6.1절)
#   CacheSerializationTest: record·BigDecimal·Instant 왕복
#   CacheEvictionTest     : 배치가 파생값을 바꿀 때만 evict
#   CaffeineCacheTest     : 로컬 캐시 경로 (Redis 없이 돈다)

# 2. 스택 기동 + 대량 데이터 (006 "재현 방법" 1~3번과 동일)
cd .. && docker compose -f docker-compose.prod.yml --profile monitoring up -d --build

# 3. 군별 측정. CACHE_TYPE을 바꿀 때마다 backend를 재생성하고 nginx를 재시작한다
#    (안 하면 nginx가 옛 IP를 물고 502를 뱉는다 — 005의 함정)
CACHE_TYPE=redis docker compose -f docker-compose.prod.yml up -d backend1 backend2
docker compose -f docker-compose.prod.yml restart nginx

# 4. 시나리오마다 **워밍업을 먼저 돌리고 버린다** (2.2절). 이게 없으면 1회차가 2~3ms 높다
docker compose -f docker-compose.prod.yml --profile load run --rm --no-deps \
  -e DURATION=20s -e RAMP_UP=5s -e RAMP_DOWN=5s \
  k6 run -o experimental-prometheus-rw /scripts/seatmap.js

# 5. 본 측정 3회, 사이에 98-reset
K6_RUN_LABEL=t029-d-redis docker compose -f docker-compose.prod.yml --profile load run --rm --no-deps \
  k6 run -o experimental-prometheus-rw --summary-export=/results/t029-d-redis-seatmap-1.json \
  /scripts/seatmap.js
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/98-reset-reservations.sql

# 6. 적중률과 메모리
curl -s localhost:8080/actuator/prometheus | grep cache_gets
docker exec tikkit-redis-prod redis-cli info memory | grep used_memory_human
docker exec tikkit-redis-prod redis-cli --scan --pattern 'tikkit:cache:*' | head
docker exec tikkit-redis-prod redis-cli pttl 'tikkit:cache:performanceDetail::1'
```

Grafana(`localhost:3001`)의 **캐시 적중률**과 **좌석맵 생성 시간 vs 전체 응답 p95** 패널을
같은 타임라인으로 본다. 적중률 시리즈가 아예 없으면 `CACHE_TYPE=none`이거나
`enableStatistics()`가 빠진 것이다.
