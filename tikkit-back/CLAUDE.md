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
| Redis | 7 | 캐시·대기열 (Task 020 도입, `REDIS_ENABLED=true`일 때만 연결) |
| Redisson | 4.8.0 | Redis 클라이언트 |
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
- 세션 기반 인증(`HttpSession`). 서버를 여러 대로 늘릴 때 생기는 세션 불일치와 JWT 전환 검토는 ROADMAP Task 026 참조
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

Redis는 `tikkit.redis.enabled`가 기본 `false`라 애플리케이션이 연결하지 않는다. Redis 없이도 기동과 테스트가 돌아야 하기 때문이다 — 이유는 `docs/improvements/003-redis-distributed-lock.md` 3절. 쓰려면 `REDIS_ENABLED=true`로 실행한다. 캐시(Task 029)·대기열(Task 031)에서 본격적으로 쓴다.

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
