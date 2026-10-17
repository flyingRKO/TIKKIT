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
| Redis | 7 | 세션(Task 026)·조회 캐시(Task 029)·대기열(Task 031 예정) |
| Redisson | 4.8.0 | Redis 클라이언트 (대기열용. 세션·캐시는 Lettuce를 쓴다) |
| Spring Cache | - | 조회 캐시 추상화 (Task 029). 저장소는 `tikkit.cache.type`으로 고른다 |
| Caffeine | - | 로컬 캐시 (Task 029 비교 실험군) |
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
./gradlew test              # 테스트 실행
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
| `tikkit.redis.enabled=true` | Redisson 클라이언트 (대기열, Task 031 예정) | `false` |

주소는 `tikkit.redis.address` 하나를 공유한다("Redis가 어디 있나"는 하나의 사실이다).
Lettuce 연결 팩토리는 `common/config/RedisConnectionConfig`가 만들고, **세션과 캐시 중 하나라도
`redis`면** 생성된다(`AnyNestedCondition`으로 OR). 둘을 각각 켤 수 있어야 해서 AND가 아니다.

## 조회 캐시 (Task 029)

저장소는 `tikkit.cache.type`으로 고른다 — `none`(기본) | `caffeine` | `redis`.
기본값이 `none`인 이유는 `session-store`와 같다: Redis 없이도 기동·테스트가 돌아야 한다
(`docs/improvements/003-redis-distributed-lock.md` 3절). `none`은 `NoOpCacheManager`라
`@Cacheable`이 전부 통과만 하므로, 어노테이션을 지우지 않고도 캐시가 완전히 꺼진다.

캐시 이름·TTL·evict 대상은 **`common/config/CacheNames.java` 한 곳**에 모여 있다.
저장소 선택은 `CacheConfig` 한 곳이고, 서비스 코드는 저장소를 모른다.

**캐시하는 것은 "변하지 않는 것"뿐이다.**

| 캐시 | 값 | TTL |
|---|---|---|
| `performanceList` / `performanceDetail` | 공연 목록·상세 | 60s (배치 주기와 같다) |
| `gradePrices` | 등급·가격 (잔여 수량 제외) | 10m |
| `seatLayout` | 좌석 좌표·등급 (status 제외) | 10m |

**캐시하지 않는 것**: 좌석 상태(`status`), 잔여 수량(`remainingQuantity`), keyword 검색 결과,
없는 회차의 빈 결과. 좌석맵과 등급 조회는 캐시된 불변 부분과 실시간 조회를 `ScheduleService`가
합쳐서 내려준다 — 그래서 선점 직후 재조회에 `HELD`가 즉시 보인다.

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

**Redis는 세션과 캐시가 공유한다.** `maxmemory` + `allkeys-lru`를 걸어서 캐시가 상한을 채우면
세션 키도 축출될 수 있다. 캐시 전용 인스턴스로 분리하지 않은 이유는 008 8절에 있다 —
`RedisConnectionFactory` 빈이 둘이 되면 Spring Session 자동설정이 캐시 팩토리에도 걸려서
"캐시만 켠다"가 세션까지 켠다.

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
