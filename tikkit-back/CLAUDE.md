# TIKKIT 백엔드 개발 지침

## 핵심 기술 스택

| 기술 | 버전 | 용도 |
|------|------|------|
| Spring Boot | 4.1.1 | 메인 애플리케이션 프레임워크 |
| Java | 21 | 백엔드 언어 |
| Spring Data JPA | Hibernate 7.4 | ORM / 데이터 접근 레이어 |
| QueryDSL | 5.1.0 (jakarta) | 타입 안전 동적 쿼리 |
| MyBatis | 4.1.0 (starter) | 통계/배치/벤더 특화 SQL |
| Spring Security | - | 인증 및 인가 (세션 기반, Task 008에서 도입) |
| Spring Web | - | REST API (`spring-boot-starter-webmvc`) |
| PostgreSQL | 15 | 메인 데이터베이스 (Docker) |
| Redis | 7 | 세션(Task 026)·조회 캐시(Task 029)·대기열(Task 031) |
| Lettuce | - | 유일한 Redis 클라이언트. 연결 팩토리는 `RedisConnectionConfig`가 만든다 |
| Spring Cache | - | 조회 캐시 추상화 (Task 029). 저장소는 `tikkit.cache.type`으로 고른다 |
| Actuator + Micrometer | - | 헬스·지표 노출 (`/actuator/health`, `/actuator/prometheus`, Task 025) |
| Lombok | - | 보일러플레이트 코드 감소 |
| JUnit 5 | - | 테스트 프레임워크 |

## 프로젝트 구조

```
tikkit-back/
├── src/
│   ├── main/
│   │   ├── java/com/tikkit/api/
│   │   │   ├── TikkitApplication.java      # 메인 클래스
│   │   │   ├── domain/                     # 도메인 모듈
│   │   │   │   └── {domain}/
│   │   │   │       ├── controller/         # REST 컨트롤러
│   │   │   │       ├── service/            # 비즈니스 로직
│   │   │   │       ├── repository/         # JPA/QueryDSL 데이터 접근
│   │   │   │       ├── mapper/             # MyBatis 매퍼 (통계/배치/벤더 특화)
│   │   │   │       ├── entity/             # JPA 엔티티
│   │   │   │       └── dto/                # 요청/응답 DTO
│   │   │   ├── common/                     # 공통 모듈
│   │   │   │   ├── exception/              # 예외 처리
│   │   │   │   ├── response/               # 공통 응답 형식
│   │   │   │   └── config/                 # 설정 클래스
│   │   │   └── security/                   # 보안 설정
│   │   └── resources/
│   │       ├── application.yml             # 프로필 설정
│   │       ├── application-dev.yml         # 개발 환경
│   │       └── application-test.yml        # 테스트 환경
│   └── test/
│       └── java/com/tikkit/api/
│           ├── domain/{도메인}/{service,repository,entity}/  # 유닛 테스트 (main 패키지 미러링)
│           ├── integration/{도메인}/       # 통합 테스트 (전체 스프링 컨텍스트, 컨트롤러 포함)
│           └── support/                    # AbstractContainerTest 등 테스트 공통 베이스
└── build.gradle
```

## 개발 규칙

### 레이어드 아키텍처 준수

```
Controller → Service → Repository → Entity
```

- **Controller**: HTTP 요청/응답, 입력 검증, 인증 확인
- **Service**: 비즈니스 로직, 트랜잭션 관리
- **Repository**: 데이터 접근 — JPA/QueryDSL (CRUD, 동적 조건 조회)
- **Mapper**: MyBatis 매퍼 — 통계/리포트/배치/벤더 특화 SQL 한정
- **Entity**: JPA 매핑, 도메인 모델

### ORM 선택 기준

| 작업 유형 | 담당 |
|---------|------|
| 쓰기 (INSERT/UPDATE/DELETE) | JPA |
| 단순 조회 / 동적 조건 조회 | QueryDSL |
| 통계 / 리포트 / 집계 | MyBatis |
| 대용량 배치 | MyBatis |
| DB 벤더 특화 SQL | MyBatis |
| 레거시 스키마 매핑 | MyBatis |

### QueryDSL 사용 규칙

- **Custom/Impl 분리**: `XxxRepository extends JpaRepository<X, Long>, XxxRepositoryCustom` (인터페이스에는 `@Repository` 붙이지 않음) + `XxxRepositoryImpl implements XxxRepositoryCustom` (`@RequiredArgsConstructor`로 `JPAQueryFactory` 주입, `private static final QX x = QX.x;` 로 Q타입 참조)
- **동적 조건**: `BooleanBuilder`로 조건을 조합하거나, null-safe한 `BooleanExpression` 반환 메서드(`xxxEq`, `xxxContains`)로 분리한다. `null`을 리턴하면 `where()`에서 자동으로 무시된다
- **페이징**: content 쿼리와 count 쿼리를 분리하고 `PageableExecutionUtils.getPage(content, pageable, () -> countQuery)`로 최적화한다. count 쿼리에서 불필요한 join은 뺀다
- **금지**: `fetchResults()` / `fetchCount()` — Querydsl 5.0부터 Deprecated이며 복잡한 쿼리에서 count 쿼리를 잘못 생성한다
- **N+1 방지**: 연관관계를 함께 조회해야 하면 `fetchJoin()`을 쓴다. 연관관계는 기본 LAZY 유지
- **프로젝션**: 결과 컬럼이 여러 개면 DTO(record)를 `Projections.constructor`로 매핑한다. `Tuple`을 리포지토리 밖으로 노출하지 않는다. `@QueryProjection`은 DTO가 QueryDSL에 의존하게 되므로 쓰지 않는다
- **쓰지 않는 것**: `QuerydslRepositorySupport`, `QuerydslPredicateExecutor`, Querydsl Web 지원 — 조인 제약(특히 `QuerydslPredicateExecutor`는 left join 불가)과 기술 의존성 노출 문제로 실무에 부적합하다

### 코딩 컨벤션

- 주석은 한국어로 작성
- 변수명/메서드명은 영어 사용 (camelCase)
- 클래스명은 PascalCase
- 상수는 UPPER_SNAKE_CASE
- Lombok 어노테이션 적극 활용 (@Builder, @RequiredArgsConstructor)

### API 설계 원칙

- RESTful URL 구조: `/api/v1/{resource}`
- HTTP 상태 코드 올바르게 사용
- 일관된 응답 형식 (`ApiResponse<T>`)
- DTO를 사용하여 Entity 직접 노출 금지

### 보안 규칙

- 민감한 데이터는 응답에 포함하지 않음
- 모든 비밀번호는 BCrypt 암호화
- 인증 상태 저장 전략은 `tikkit.auth.session-store`로 고른다 — `memory`(기본) | `redis` | `jwt`.
  전략을 고르는 자리는 `security/AuthStrategyConfig` **한 곳**이고, 로그인/로그아웃에서 무엇을 남기는지는
  `security/login/LoginCredentialWriter` 구현이 정한다. 컨트롤러·서비스는 전략을 모른다.
  BE를 2대 이상으로 띄우면 `memory`는 세션 불일치가 난다 — 비교·측정은 `docs/improvements/005-session-scaling.md` 참조
- 새 `@Scheduled` 배치를 추가하면 **반드시 `@SchedulerLock`을 붙인다.** BE가 2대라 안 붙이면 양쪽에서 돈다.
  기존 만료 배치는 조건부 UPDATE라 두 번 돌아도 데이터가 안 깨지지만, 알림 발송 같은 비멱등 작업은 그대로 사고다
- SQL Injection 방지 (JPA 파라미터 바인딩)

## Gradle 스크립트

```bash
./gradlew bootRun           # 개발 서버 실행 (포트: 8080)
./gradlew build             # 프로덕션 빌드
./gradlew test              # 테스트 실행 (쿼리 카운트 제외 — Task 042_1)
./gradlew queryCountTest    # 경로별 SQL 문장 수 게이트 (Task 042_1)
./gradlew check             # 전부 (test + queryCountTest)
./gradlew clean             # 빌드 산출물 삭제
```

## 데이터베이스

최초 1회 `.env.example`을 복사해 `.env`를 만든다 (`.env`는 gitignore 대상이라 저장소에는 올라가지 않는다):

```bash
cp .env.example .env
```

```bash
# PostgreSQL + Redis 컨테이너 실행 (Docker Desktop이 켜져 있어야 한다)
docker-compose up -d

# 접속 정보 (application-dev.yml 참조)
# Host: localhost
# Port: 5432
# Database: tikkit_db
```

DB 자격증명은 `application-dev.yml`에서 `${DB_USERNAME:tikkit_user}` 형태의 기본값을 쓴다. 평소엔 아무 설정 없이 `.env`의 값과 동일하게 동작하고, 필요할 때만(CI 등) `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 환경변수로 재정의한다.

Redis에 연결하는 토글이 **세 개**인데 서로 독립이다. 전부 기본값이 "연결하지 않음"이고, 이유는
Redis 없이도 기동과 테스트가 돌아야 하기 때문이다(`docs/improvements/003-redis-distributed-lock.md` 3절).

| 프로퍼티 | 용도 | 기본값 |
|---|---|---|
| `tikkit.auth.session-store=redis` | Spring Session (Task 026) | `memory` |
| `tikkit.cache.type=redis` | 조회 캐시 (Task 029) | `none` |
| `tikkit.queue.enabled=true` | 대기열 (Task 031) | `false` |

주소는 `tikkit.redis.address` 하나를 공유한다("Redis가 어디 있나"는 하나의 사실이다).
Lettuce 연결 팩토리는 `common/config/RedisConnectionConfig`가 만들고, **하나라도 Redis를
요구하면** 생성된다(`AnyNestedCondition`으로 OR). 각각 켤 수 있어야 해서 AND가 아니다.

Task 020이 쓰던 `tikkit.redis.enabled`(Redisson 클라이언트)는 **Task 031에서 제거했다** —
분산 락을 기각한 뒤로 주입처가 0개였고, 대기열도 Lettuce로 간다. 003 8절이 "Redis를 락이 아니라
원래 잘하는 일(TTL 캐시, 정렬 집합)로 쓴다"고 예고한 그대로다.

## 조회 캐시 (Task 029)

저장소는 `tikkit.cache.type`으로 고른다 — `none`(기본) | `redis`.
기본값이 `none`인 이유는 `session-store`와 같다: Redis 없이도 기동·테스트가 돌아야 한다
(`docs/improvements/003-redis-distributed-lock.md` 3절). `none`은 `NoOpCacheManager`라
`@Cacheable`이 전부 통과만 하므로, 어노테이션을 지우지 않고도 캐시가 완전히 꺼진다 —
"캐시 없는 기준선"을 언제든 다시 잴 수 있다.

캐시 이름·TTL·evict 대상은 **`common/config/CacheNames.java` 한 곳**에 모여 있다.
저장소 선택은 `CacheConfig` 한 곳이고, 서비스 코드는 저장소를 모른다.

**캐시하는 것은 "변하지 않고 작은 것"뿐이다.**

| 캐시 | 값 | 항목 크기 | TTL |
|---|---|---|---|
| `performanceList` / `performanceDetail` | 공연 목록·상세 | 수백 B ~ 수 KB | 60s (배치 주기와 같다) |
| `gradePrices` | 등급·가격 (잔여 수량 제외) | 수백 B | 10m |

**캐시하지 않는 것**: 좌석 상태(`status`), 잔여 수량(`remainingQuantity`), keyword 검색 결과,
없는 회차의 빈 결과. 등급 조회는 캐시된 가격과 실시간 COUNT를 `ScheduleService`가 합쳐서
내려준다 — 그래서 매진이 TTL만큼 늦게 보이는 일이 없다.

**좌석맵은 이 Redis 캐시가 아니라 별도 로컬 캐시로 간다**(Task 036, 아래 절). 여기 있는
`CacheManager`에는 등록되지 않는다.

**좌석맵을 Redis에 올리는 것은 기각이다.** 좌표·등급은 불변이라 캐시할 수 있는 모양이었지만,
항목이 2,000석에 337,885바이트여서 Redis 왕복·역직렬화가 원래 쿼리보다 비쌌다 —
**적중률 99.97%에서도 캐시를 켠 쪽이 느렸다**(좌석맵 p95 6.63 → 7.22ms). 그래서 **새 캐시를
넣을 때는 항목 크기를 같이 본다** — 값이 커지면 캐시가 아끼는 DB 시간보다 네트워크·역직렬화
비용이 먼저 커진다.

008은 "좌석맵의 확장성 해법은 캐싱이 아니라 응답 크기를 줄이는 것"이라고 적었는데,
**Task 033이 그 처방도 정정했다** — 응답 크기도 아니었다. 바이트를 49.4% 줄여도 처리량이
1~7%만 올랐고, 좌석맵 비용의 **68%가 DB 조회 + DTO 조립**이다. 레버는 2,000행을 꺼내 객체로
만드는 비용 그 자체다.

**저부하에서는 켜도 p95가 좋아지지 않는다.** 006 기준으로 BE CPU가 최대 4.2%라 요청당 고정비가
지연을 지배한다. 채택 근거는 지연이 아니라 **용량**(히트가 DB 왕복을 없앤다)이고,
**Task 033이 포화까지 올려 증명했다**:

| 시나리오 | 캐시 없음 | 캐시 켬 |
|---|---|---|
| `browse` (균등 분포) | 7,400 req/s | **10,150 req/s (+37%)** |
| `browse-skewed` (치우침) | 7,537 req/s | **10,655 req/s (+41%)** |
| `seatmap` (캐시 대상 아님) | 842 req/s | 846 req/s (+0.4%) |

적중률 99.9%이고 같은 VU에서 p95도 같이 내려간다. 치우친 분포가 더 유리한 것도 방향이 맞다.
기본값을 `none`으로 유지하는 이유는 기준선을 다시 잴 수 있어야 하기 때문이다.
(`docs/improvements/010-waiting-queue.md` 8절)

새 캐시를 추가할 때 주의할 점 넷. 전부 **조용히 틀리는** 것들이라 실제로 한 번씩 겪었다
(`docs/improvements/008-caching.md` 6절).

- **`RedisCacheManager.builder()`에서 `withInitialCacheConfigurations` 뒤에 `initialCacheNames`를
  부르면 캐시별 TTL이 `cacheDefaults`로 덮인다.** `PTTL`이 전부 `-1`이 되는데 설정 코드만
  읽으면 멀쩡해 보인다. `CacheTtlTest`가 Redis 키의 남은 수명을 직접 읽어 고정한다.
- **같은 클래스 안에서 `@Cacheable` 메서드를 호출하면 프록시를 타지 않는다.** 예외도 경고도
  없고 적중률만 0%다. 캐시 대상과 그걸 쓰는 조립 로직이 한 메서드에 있어야 한다면
  `CatalogCache`처럼 빈을 분리한다.
- **캐시 키에 SpEL의 `null` 문자열화를 기대면 안 된다.** `(#category ?: '-')`처럼 명시한다.
- **키가 경로 변수면 사용자가 키 공간을 고를 수 있다.** 빈 결과를 캐시하면 무작위 id로
  캐시를 채울 수 있으므로 `unless = "#result.isEmpty()"`를 붙인다.

**쿼리 카운트 테스트가 `cache.type`에 묶여 있다.** 캐시 off 기준값은
`PerformanceQueryCountTest`, Redis 캐시의 미스/히트 경로는 `CacheQueryCountTest`가 따로 고정한다.
캐시 대상을 바꾸면 양쪽을 같이 고쳐야 한다.

**Redis는 세션과 캐시가 공유한다.** `maxmemory` + `allkeys-lru`는 OOM 가드이고, 조회 캐시는
카탈로그 전체가 올라와도 수십 MB라서 상한을 채우지 않는다(측정 중 피크 43.3MB, 축출 0건).
축출이 일어나면 세션 키도 대상이므로, Task 031 대기열로 메모리가 커지면 세션과 분리할지 다시
판단한다. 분리가 공짜가 아닌 이유는 008 8절에 있다 — `RedisConnectionFactory` 빈이 둘이 되면
Spring Session 자동설정이 캐시 팩토리에도 걸려서 "캐시만 켠다"가 세션까지 켠다.

## 좌석맵 로컬 캐시 (Task 036)

**그 레버를 로컬 캐시로 잡았다.** `tikkit.cache.seatmap`으로 고른다 — `single`(기본) | `local`.

| 지표 | `single` | `local` |
|---|---|---|
| 포화 처리량 | 768.6 req/s | **995.1 req/s (+29.5%)** |
| Postgres CPU (포화 중앙) | 390.1% | **144.6% (-62.9%)** |
| DB 내부 실행 시간/요청 | 14.85 ms | **1.98 ms (-86.7%)** |
| 적중률 | — | 99.4% |

**029가 Redis로 기각한 것과 결론이 다른 이유는 저장소다.** 로컬에는 직렬화도 네트워크 왕복도
없고, 029의 같은 측정에서 Caffeine은 이미 네 군 중 가장 빨랐다(4.98ms). 그때 안 고른 이유는
힙 용량과 분산 evict였는데, **evict 쪽은 이 설계에 적용되지 않는다.**

**캐시하는 것은 불변 7필드뿐이다** — 좌표·구역·행·번호·등급 id. 그 필드를 런타임에 바꾸는 코드
경로가 **0건**이라(상태 UPDATE 3개와 만료 배치는 `status`·`reservation_id`·`updated_at`만 SET하고
`Seat`·`ScheduleSeat`에 변경 메서드가 없다) **`@CacheEvict`가 없다.** 상태는 매 요청 DB에서 읽어
`ScheduleService`가 합친다 — `gradePrices`가 가격만 캐시하고 잔여 수량을 매번 세는 것과 같은 선이고,
그래서 선점 직후 재조회에 `HELD`가 즉시 보인다.

주의할 점 셋.

- **`CacheManager`가 둘이다.** 좌석맵은 `seatmapCacheManager`(Caffeine), 카탈로그는 기본
  `cacheManager`(Redis)다. `@Primary`가 아닌 쪽은 `@Cacheable(cacheManager = "...")`로 지정해야 하고,
  빼먹으면 Redis 쪽으로 가서 029가 기각한 그 비용을 그대로 낸다
- **`seatmap-max-entries`는 작업 집합보다 커야 한다.** 600회차를 200칸에 담은 군은 적중률 33.5%에
  처리량 +1.7%(산포 안)로 이득이 사라졌고, 축출이 만든 쓰레기 때문에 **힙을 오히려 더 썼다**
  (Old Gen 311 → 497MB). **상한을 줄여서 힙을 아낄 수 없다 — 담거나 안 하거나다.** 회차당 약 420KB다
- **기본값이 `single`인 이유는 `cache.type`이 `none`인 것과 같다** — 채택한 뒤에도 기준선을 다시
  잴 수 있어야 한다. 운영·측정에서는 `SEATMAP_STRATEGY=local`로 띄운다

쿼리 카운트는 `SeatmapLocalCacheQueryCountTest`가 미스 3문장 / 히트 2문장으로 고정한다.
**히트가 1이 아닌 게 핵심이다** — 상태를 캐시하면 팔린 좌석이 TTL 동안 "선택 가능"으로 보인다.
(`docs/improvements/014-seatmap-assembly-cost.md`)

## 대기열 (Task 031)

`tikkit.queue.enabled`로 켠다 — `false`(기본)면 `DisabledQueueGateway`가 올라가 **전부 통과**시키므로
호출부를 지우지 않고도 기능이 완전히 꺼진다. `cache.type=none`이 `NoOpCacheManager`를 올리는 것과
같은 구조다. 구현을 고르는 자리는 `domain/queue/config/QueueStrategyConfig` **한 곳**이고,
Redis가 필요한 빈 전체가 그 안의 중첩 설정에 묶여 있다 — 빈마다 조건을 흩으면 "줄은 서는데 아무도
입장하지 않는" 조합이 조용히 만들어진다.

토글이 **두 층**이다. 둘을 혼동하면 디버깅이 길어진다.

| 설정 | 의미 |
|---|---|
| `tikkit.queue.enabled` | 대기열 기능이 이 **애플리케이션**에 있는가 |
| `schedules.queue_enabled` | 이 **회차**에 줄을 세우는가 (V8, 사람이 SQL로 켠다) |

앞이 꺼져 있으면 뒤가 true여도 아무 일도 일어나지 않는다.

### Redis 키는 회차당 두 개다

```
tikkit:queue:{12}:waiting   ZSET  member = memberId, score = 진입 epoch millis
tikkit:queue:{12}:active    ZSET  member = memberId, score = 입장권 만료 epoch millis
```

중괄호는 **리터럴**이다(Redis Cluster hash tag). 지금은 standalone이라 영향이 없지만, 나중에 키
포맷을 바꾸면 운영 중에 줄 서 있는 사람들의 큐가 사라진다. 이름은 `QueueKeys` 한 곳에 모은다.

**활성 쪽이 SET + 토큰 키가 아니라 ZSET이다.** 로드맵 문구는 "활성 세트 + TTL 토큰"이었는데, 그러면
토큰 키가 만료돼도 SET에 멤버가 영구히 남아 `SCARD`가 틀린다. score에 만료 시각을 넣으면 검증이
`ZSCORE` 왕복 1회, 일괄 수거가 `ZREMRANGEBYSCORE` 명령 1개, 연장이 `ZADD GT` 명령 1개가 된다.
테스트에서 만료를 `sleep` 없이 과거 score로 재현할 수 있는 것도 이 설계 덕이다.

**별도 토큰을 발급하지 않는다.** 입장권의 실체는 `active` ZSET의 `(memberId → 만료시각)` 엔트리이고
전송되는 비밀이 없다. 랜덤 UUID 토큰은 같은 인증 세션에 내려줄 수밖에 없어서 보안 이득이 0이다 —
새 비밀을 만드는 게 아니라 같은 비밀을 두 번 적는 것이다.

### 입장 순서가 설계의 핵심이다

스케줄러 한 틱: `ZREMRANGEBYSCORE active`(만료 수거) → `ZRANGE waiting`(선두 N명) →
**`ZADD active`(먼저)** → **`ZREM waiting`(나중)**.

두 명령 사이에서 죽으면 사용자가 양쪽에 남는데, 상태 조회가 `active`를 먼저 보므로 **정상 입장**한다.
남은 중복은 다음 틱이 치운다(`ZADD`가 score만 갱신하고 `ZREM`이 대기를 지우므로 분기가 필요 없다).
거꾸로 하면 양쪽에서 사라져 **선두 몇 명이 조용히 맨 뒤로 간다** — 설명할 수 없고 로그에도 안 남는다.
멱등성 방향을 at-most-once가 아니라 **at-least-once로 고정**하는 것이다.

**Lua를 쓰지 않는다.** Lua도 "프로세스가 죽는 것"은 못 막고 비원자 창을 0.3ms에서 0으로 줄일 뿐인데,
그 창에 끊긴 결과가 이미 무해하다. ShedLock이 단일 writer를 보장하므로 경쟁 자체도 없다.
Lua가 필요해지는 조건은 ① ShedLock을 떼고 회차별 샤딩으로 갈 때 ② "빈자리가 나면 즉시 다음 사람
입장"처럼 **요청 경로**에서 활성 인원을 늘려야 할 때다(그건 ShedLock이 못 막는다).

진입은 `ZADD NX` 하나로 닫힌다 — 더블클릭·폴링 중복·새로고침이 순번을 밀지 않고, 재진입으로
앞자리를 살 수도 없다.

### 보호 대상은 둘뿐이다

| 엔드포인트 | 보호 | 수단 |
|---|---|---|
| `GET /schedules/{id}/seats` | ✅ | `QueuePassInterceptor` |
| `POST /reservations` | ✅ | `ReservationService`가 가드를 직접 호출 |
| `GET /schedules/{id}/ticket-grades` | ❌ | 대기열 화면이 공연·가격을 보여줘야 한다 |
| 결제·취소 | ❌ | **이미 좌석을 잡은 사람은 거래를 끝낼 수 있어야 한다** |

`POST /reservations`가 인터셉터로 안 되는 이유: `scheduleId`가 **요청 본문**에 있어서 인터셉터가
읽으면 `@RequestBody` 역직렬화가 빈 스트림을 만난다. AOP로 숨기지 않은 이유는 "왜 403인지"
추적이 안 되고 Task 020이 지운 `@DistributedLock` AOP를 되살리는 셈이기 때문이다.

**대기열을 켠 회차는 로그인이 필수다.** 좌석 조회는 `permitAll`인데(Task 022가 "자리를 보고 나서
가입하는 흐름이 정상"이라고 의도적으로 열었다) `memberId`로 줄을 세워야 1인 1자리가 성립한다.
`SecurityConfig`를 `authenticated()`로 올리지 않고 **인터셉터가 401을 던지는** 이유가 이것이다 —
그러면 플래그가 꺼진 회차의 공개 조회가 그대로 보존된다.

### `queue_enabled`를 요청마다 DB에서 읽지 않는다

`QueueFlagRegistry`가 `volatile` 스냅샷 + 조회 시 자기 갱신(TTL 10초)으로 캐시한다.

- **Spring Cache를 못 쓴다** — `cache.type` 기본값이 `none`이라 거기 얹으면 캐시를 끈 환경에서
  좌석 조회마다 DB로 간다. 세 토글이 독립이어야 한다는 전제가 깨진다.
- **`@Scheduled`로 갱신하면 안 된다** — `SchedulingConfig`가 `@Profile("!test")`라 테스트에서
  스냅샷이 영원히 비고 **대기열 테스트 전체가 "플래그 off"로 조용히 통과한다.** 게다가 JVM 로컬
  상태라 인스턴스마다 돌아야 해서 `@SchedulerLock`을 붙일 수 없는데, 그건 "새 `@Scheduled`에는
  반드시 락" 규칙의 예외가 된다.
- 스탬피드(TTL 직후 중복 재적재)는 **의도적으로 감수한다.** 쿼리가 ms 단위이고, 락으로 막으면
  모든 조회가 락을 지난다.

### 폴링 간격을 백엔드가 지시한다

응답의 `pollAfterSeconds`로 내려준다. **구간이 넷이다**(입장·만료 시 0):

| 순번(0-based rank) | 간격 |
|---|---|
| `< 100` | 2초 |
| `< 1,000` | 5초 |
| `< 10,000` | 10초 |
| 그 이상 | **60초** |

10만 명이 2초 고정으로 폴링하면 상태 API 하나에 **50,000 RPS**가 몰려서, 좌석 API를 막아 번 여유를
상태 API가 전부 먹는다. Redis는 버티지만 Tomcat 스레드와 nginx가 먼저 죽는다.

**4번째 구간은 Task 033이 측정으로 넣었다.** 3구간(2/5/10초)은 10만 명에서 10,130 RPS를
요구했는데 실측한 상태 API 처리량이 2,986 req/s(p95 3.63ms)였다 — 3구간이 지탱하는 한계는
**약 28,000명**으로 목표의 1/3이다. 60초를 넣으면 요구가 2,630 RPS로 떨어져 실측값 안에 들어온다.
30초(4,130 RPS)가 체감에 낫지만 증명되지 않은 영역이라 증명된 쪽을 골랐다.
근거와 한계는 `docs/improvements/010-waiting-queue.md` 6절.

**숫자를 바꿀 때 FE는 고치지 않는다.** 값이 계약(`pollAfterSeconds`)으로 내려가고 FE가
`setInterval`이 아니라 `setTimeout` 체인으로 구현했기 때문이다. 상수는
`RedisQueueGateway`의 `NEAR_FRONT_RANK`/`MID_RANK`/`FAR_RANK`/`POLL_*_SECONDS`이고,
구간 경계는 `QueueOrderingTest`가 양쪽 값으로 고정한다.

예상 대기시간은 `rank / 초당 입장 인원`이고 **공칭 속도 기준이라 실제보다 낙관적이다.**
한동안 그 원인을 `fixedDelay`로 적어뒀는데 **Task 033이 반증했다** — 배치 5~100에서 실제 입장
속도가 공칭값의 1.000~1.033배였다(작업이 수 ms라 1초에 묻힌다). 진짜 원인은 **유령**이다.
관측 속도로 계산하지 않는 이유는 분모가 흔들리면 ETA의 단조 비증가가 깨지기 때문이다 —
진행바가 되감기면 사용자는 버그로 읽는다.

### 입장 속도: `admit-batch-size`는 회차당이다

```
초당 입장 인원 = admit-batch-size x 켠 회차 수 / admit-interval
```

`QueueAdmissionScheduler.run()`이 `queue_enabled`가 켜진 회차를 **전부 순회**하면서 회차마다
배치를 입장시킨다. Task 033이 실측으로 도출한 유효 구간은 **[6.7, 84]명/s**다.

- 상한 84 = `R_sat`(도착률 120/s) x 안전계수 0.7. 그 지점에서 좌석 조회 + 선점이 p95 200ms 안에
  처리된다(309 req/s, 122 MB/s)
- 하한 6.7 = 10,000석 / 2.5석 / 600초(목표 판매 시간)

**기본값 50은 회차 1개를 전제한 값이다.** 회차를 2개 켜면 100명/s가 되어 상한을 넘는다 —
여러 회차를 동시에 켤 거면 `batch x N <= 84`를 지켜야 한다. 이게 전역 상수인 것 자체가 의심스럽고
후속 후보로 남겼다.

### 거절은 트랜잭션 밖에서 한다

`POST /reservations`의 입장권 가드는 **`ReservationController`에 있다.** Task 031에서는
`ReservationService.create()`의 첫 줄이었는데, 그 메서드가 `@Transactional`이라
**거절 한 건이 Hikari 커넥션 한 개를 잡고 나서 던졌다** — SQL을 한 줄도 돌리지 않는데도
트랜잭션 시작 시점에 획득하기 때문이다. 요청당 1.0114개였고, 옮긴 뒤 0.0173개다.

대기열의 목적이 DB를 지키는 것인데 거절이 그 자원을 소모하고 있었다. **새 가드를 추가할 때
트랜잭션 경계를 먼저 본다.** 대가는 서비스가 스스로를 보호하지 않는다는 것이고,
`create()`의 호출부가 늘어나면 가드를 같이 옮겨야 한다.

### 알려진 한계

- **입장권에 절대 상한이 없다.** 활동하면 무한 연장되므로 봇이 슬롯을 영구 점유할 수 있다.
  상한을 두려면 `admittedAt`용 ZSET이 하나 더 필요하다(한 score에 두 시각을 담을 수 없다).
  `booking_close_at`이 사실상 상한 역할을 한다.
- **유령을 치우지 않는다.** 브라우저를 닫으면 `waiting` 엔트리가 남아 그 틱의 슬롯을 낭비하고
  ETA가 과대 추정된다(사용자에게는 "생각보다 빨리 들어왔다"라 안전한 방향). 용량 기반 정책으로
  바꾸면 `seen` ZSET이 필수가 되고, 그때 임계값은 **90초 이상**이어야 한다 — 브라우저가 백그라운드
  탭 타이머를 1분에 1회로 스로틀하므로 짧으면 정상 사용자를 쫓아낸다.
- **메모리 압박의 1차 희생자는 큐가 아니라 세션이다.** Task 033이 `maxmemory`를 낮춰 재현했다.
  대기 10만 명은 **9.24 MiB**(96.9 B/멤버, skiplist)라 상한 512MB에서 `evicted_keys`가 0이지만,
  상한을 16mb로 내리면 **세션 9,999개가 축출되고 큐는 살아남는다** — 폴링이 큐 키를 계속 만져서
  `allkeys-lru`가 핫한 큐를 지키기 때문이다. 그러면 줄 서 있던 사람이 큐는 유지된 채
  **다음 폴링에서 401을 받는다.** 6mb까지 내리면 그때 **큐가 통째로** 사라지고(ZSET 한 개라
  부분 축출이 없다) 전원이 꼬리로 재진입한다. 세션 Redis 분리는 후속 후보다 — 분리가 공짜가
  아닌 이유는 008 8절에 있다.
- **활성 인원 상한이 없다.** 입장 속도가 좌석 API 처리량보다 크면 대기열이 지연을 늦추기만 하고
  막지 못한다. Task 033이 배치 5~100 스윕으로 재현했다 — **배치를 키우면 그만큼 그대로
  흘러들어간다.** 대기열은 유입을 차단하는 장치가 아니라 계량하는 장치다.
- **동시 진입의 상대 순서는 보장하지 않는다.** score가 epoch millis라 같은 ms에 둘이 들어오면
  멤버 문자열 사전순으로 갈린다(`"10" < "9"`).

## 모니터링 (Task 025)

actuator가 여는 엔드포인트는 **`/actuator/health`와 `/actuator/prometheus` 둘뿐**이다.
`management.endpoints.access.default: none`이라 나머지는 컨텍스트에 아예 없고,
`SecurityConfig`가 이 둘만 `permitAll`로 연다. 엔드포인트를 더 노출하려면 두 곳을 같이 고쳐야 한다.

```bash
curl -s localhost:8080/actuator/health              # dev는 show-details: always
curl -s localhost:8080/actuator/prometheus | head   # 지표 원본
```

주의할 점 셋.

- **`http.server.requests`에 actuator 경로도 잡힌다.** 대시보드 PromQL은
  `uri!~"/actuator.*"`로 걸러야 한다 (compose 헬스체크가 30초마다 때린다).
- **버킷 범위(`minimum/maximum-expected-value`)를 잘못 잡으면 p95가 값이 아니라 버킷 경계를
  읽는다.** 처음 5ms로 뒀다가 모든 엔드포인트가 4.8ms로 같게 나온 적이 있다
  ([004_3](../docs/improvements/004_3-monitoring.md) 4.1절). 지금은 1ms~10s다.
- **새 Counter를 추가하면 기동 시 0으로 미리 등록한다.** 한 번도 증가하지 않은 Counter는
  시리즈 자체가 없어서 Grafana가 "No data"를 띄우고, 그러면 "0건"과 "계측 없음"을 구분할 수 없다.
  `ReservationService.registerAttemptCounters()` 참고.

예매 지표는 `tikkit.reservation.attempts` 하나이고 태그가 `result`(success/failure) +
`reason`(`ErrorCode` 이름)이다. 태그 **키 집합**은 성공·실패가 같아야 한다 —
Micrometer가 같은 이름에 키 집합이 다른 미터를 거부한다.

대시보드를 띄우는 방법은 저장소 루트 README의 "운영 스택 + 모니터링" 참고.

## 에이전트 안내

백엔드 전용 에이전트는 `.claude/agents/`에 있습니다:
- `spring-boot-developer`: Spring Boot 레이어드 아키텍처 설계 전문가
- `api-designer`: REST API 설계 및 문서화 전문가

공통 에이전트는 루트 `TIKKIT/.claude/agents/`에서 상속됩니다.

## 프론트엔드 연동

프론트엔드 개발 서버: `http://localhost:3000`
CORS 설정 필요: `tikkit-front` 도메인 허용

## 환경 프로파일

| 프로파일 | 용도 | DB |
|---------|------|-----|
| `dev` | 로컬 개발 | PostgreSQL (Docker) |
| `test` | 테스트 실행 | PostgreSQL (Testcontainers) |
| `prod` | 컨테이너 기동 (Task 024) | PostgreSQL (`docker-compose.prod.yml`) |

`prod` 프로필은 dev와 세 가지가 다르다.

- **DB 접속 정보에 기본값이 없다** (`${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`).
  환경변수를 빼먹으면 기동이 실패한다 — localhost의 엉뚱한 DB를 Flyway로 건드리는 쪽이 더 비싸다.
- **시드가 환경변수 토글이다** (`${FLYWAY_LOCATIONS:classpath:db/migration}`).
  기본값은 마이그레이션만 적용한다. 로컬에서 e2e를 돌릴 때만 `classpath:db/seed`를 덧붙인다.
  `db/seed`의 버전(`V1_1`, `V4_1`, `V5_1`)이 `db/migration` 사이에 끼어 있어서
  **시드를 켠 볼륨과 끈 볼륨은 섞어 쓸 수 없다** (`down -v`로 비우고 다시 만든다).
- **swagger-ui와 `/v3/api-docs`가 꺼져 있다** (`springdoc.*.enabled: false`). 404로 응답한다.

전체 스택 기동은 저장소 루트에서 한다. `tikkit-back/docker-compose.yml`(개발 인프라)과
동시에 띄울 수 있다 — 컨테이너명·볼륨이 다르고 prod 쪽은 DB/Redis 포트를 호스트에 열지 않는다.

```bash
cd ..                       # 저장소 루트
cp .env.example .env
docker compose -f docker-compose.prod.yml up -d --build
```
