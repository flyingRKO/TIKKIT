# 004_1. Spring Boot 3.4.5 → 4.1.1 업그레이드 — 컴파일러가 못 잡는 메이저 변경 (Task 026_1)

| | |
|---|---|
| 관련 Task | Task 026_1 (Phase 7: 운영 기반) |
| 대상 코드 | `build.gradle`, Gradle 래퍼, `ApiResponse`/`PageResponse`, security 핸들러 2개, 테스트 7개 |
| before 기준선 | [004. 좌석 재고 전환](./004-seatmap-migration.md) — 동시성 수치와 HOT 비율 |
| 결론 | **동시성 정합성·API 계약 무변경**(응답 바이트까지 동일). Hibernate 7은 조건부 UPDATE를 건드리지 않았다. 반면 파괴적 변경 3건이 **컴파일을 통과했다** — 구 API가 클래스패스에 남아 있어서다 |
| 작성 시점 | 2026-10-10 |

## 1. 왜 올렸나

3.4 라인의 OSS 보안 패치가 **2025-12-31에 끊겼고** 3.5도 2026-06-30에 끝났다. 패치를 받는
라인은 4.0/4.1뿐이고 4.1.1이 최신(2026-08-20, Spring Framework 7.0.8 기반)이다.

Phase 7 맨 앞에 둔 이유는 보안만이 아니다. 뒤따르는 Task 024(Dockerfile base image·레이어 추출),
025(Actuator 엔드포인트·Micrometer 지표명), 026(세션 설정)이 전부 Boot 버전에 묶여 있다.
3.4에서 먼저 만들면 같은 자리를 두 번 손대고 측정치도 다시 뽑아야 한다.

**가장 큰 위험으로 잡은 것은 Hibernate 6.6 → 7.4였다.** Phase 5(Task 019)의 조건부 UPDATE와
Phase 6(Task 022)의 다중행 조건부 UPDATE·데이터 변경 CTE가 이 프로젝트의 동시성 정합성 전체를
받치고 있고, 전부 `@Modifying` 쿼리의 **영향 행 수 반환값**에 의존한다.

## 2. 측정 환경

| 항목 | 값 |
|---|---|
| before | Spring Boot 3.4.5 / Framework 6.2 / Hibernate 6.6 / Jackson 2 / Gradle 8.13 |
| after | Spring Boot 4.1.1 / Framework 7.0.8 / Hibernate 7.4.5 / Jackson 3.1.5 / Gradle 8.14.4 |
| Java | **21 유지** (Boot 4는 17+ 요구. 25로 올리는 건 범위 밖) |
| DB | PostgreSQL 15 (Docker), 테스트는 Testcontainers 2.0.5 싱글턴 |
| dev 데이터 | `seats` 7,120행 / `schedule_seats` 26,894행 / 구역 12종 (V1~V6 체인) |
| 측정 대상 회차 | 고척스카이돔 3,200석 (schedule_id=17) |

절대 수치는 로컬 Docker 기준이라 **before/after 상대 비교와 성립·불성립 판정**으로만 읽어야 한다.

## 3. 사전 조사 — 해당/미해당 판정

Boot 4의 파괴적 변경 목록을 먼저 조사하고 우리 코드에 대조했다. **무서웠던 항목이 대부분
해당 없었다.**

| Boot 4 변경 | 우리에게 | 왜 |
|---|---|---|
| `@MockBean`/`@SpyBean` 제거 | ❌ 해당 없음 | 사용 0건. Service는 Mockito 직접, 통합 테스트는 실제 빈 |
| `@SpringBootTest`가 MockMvc 자동 구성 중단 | ❌ 해당 없음 | MockMvc 주입 5곳 전부 이미 `@AutoConfigureMockMvc` 명시 |
| Hibernate 7 native 쿼리 날짜 타입 `java.sql`→`java.time` | ❌ 해당 없음 | native 쿼리가 반환하는 건 행 수(`int`)와 `nextval`(`long`)뿐 |
| `@Immutable` 엔티티 벌크 UPDATE 예외 | ❌ 해당 없음 | `@Immutable` 0건 |
| `@EntityScan` 패키지 이동 | ❌ 해당 없음 | 미사용 |
| `spring.jackson.read/write.*` 키 이동 | ❌ 해당 없음 | 미사용 |
| `spring-boot-starter-aop` → `-aspectj` | ❌ 해당 없음 | Task 020 실험 종료 때 이미 제거 |

**이 표가 이 문서의 절반이다.** 두 항목은 과거 결정의 배당금이다 — `@MockBean`을 한 번도 안 쓴 건
우연이 아니고(Service 단위 테스트에서 Mockito를 직접 쓰고 통합 테스트는 실제 빈으로 돌렸다),
`@AutoConfigureMockMvc`를 전부 명시한 것도 그렇다. **스프링 테스트 지원 기능을 적게 쓴 쪽이
업그레이드 비용으로 돌아왔다.**

반대로 로드맵이 Task 020 시점에 적어둔 영향 범위는 **과소 추정**이었다. Task 022가 코드를 늘렸기
때문이다.

| 항목 | 로드맵 추정 | 실측 |
|---|---|---|
| `nativeQuery` 파일 | 2파일 | **3파일 5개** (Task 022가 `ScheduleSeatRepository` 추가) |
| Jackson 직접 사용 | 6파일 | **7파일** (`ReservationConcurrencyTest` 추가) |

## 4. 핵심 발견 — 파괴적 변경 3건이 컴파일을 통과한다

메이저 업그레이드에서 흔한 직관이 "컴파일 되면 절반은 끝"이다. **이번엔 세 번 연속 깨졌다.**

### 4.1 Jackson 2가 클래스패스에 남는다

`ObjectMapper`를 Jackson 2 패키지로 import한 상태에서 `compileJava`가 **성공했다**.
`dependencyInsight`로 추적한 결과:

```
springdoc-openapi-starter-webmvc-ui:3.1.1
  └─ io.swagger.core.v3:swagger-core-jakarta:2.2.55
       └─ com.fasterxml.jackson.core:jackson-databind:2.21.5
```

springdoc이 swagger-core를 통해 Jackson 2를 끌고 오므로 Jackson 3(`tools.jackson`)과 공존하고,
구 import가 컴파일러에 걸리지 않는다. 그런데 Boot 4가 자동 구성하는 빈은 `tools.jackson`의
**`JsonMapper`**다. 즉 **컴파일은 멀쩡히 통과하고 기동 시점에 "빈이 없다"로 터지는** 구조다.

### 4.2 Testcontainers 2.0이 구 패키지를 남긴다

`testcontainers-postgresql-2.0.5.jar` 안에 클래스가 두 개 들어 있다.

```
org/testcontainers/postgresql/PostgreSQLContainer.class   ← 새 위치
org/testcontainers/containers/PostgreSQLContainer.class   ← 호환용 잔존
```

구 import가 그대로 통과한다. 4.1과 똑같은 구도다.

### 4.3 `asText()`는 deprecated로만 남았다

Jackson 3가 `asText` 계열을 `asString`으로 통일했는데, `asText()`는 `asString()`에 위임하는
`final` 메서드로 남았다. **컴파일도 통과하고 동작도 맞다** — 다음 메이저에서 터진다.

### 그래서 안전망은 deprecation 경고였다

`-Xlint:deprecation`을 명시적으로 켜서 3건을 찾았다. 켜지 않으면 조용히 남는다.

```groovy
// init 스크립트는 allprojects로 감싸야 한다 — tasks.withType만 쓰면
// "Could not get unknown property 'tasks'"로 빌드가 실패하고,
// 그 실패를 "경고 없음"으로 오독할 수 있다 (실제로 한 번 그랬다)
allprojects {
    tasks.withType(JavaCompile).configureEach {
        options.compilerArgs << "-Xlint:deprecation"
    }
}
```

> [!warning] 확인 필요
> `spring-boot-jackson2`(deprecated 호환 모듈)를 쓰지 않았으므로 우리 코드는 Jackson 3만
> 쓴다. 하지만 **swagger-core가 내부적으로 Jackson 2를 계속 쓴다.** 두 버전이 한 JVM에 공존하는
> 상태이고, springdoc이 Jackson 3로 넘어갈 때까지는 그대로다. 현재 증상은 없다.

## 5. 실제로 고친 것

### 5.1 빌드 설정

| 항목 | before | after | 이유 |
|---|---|---|---|
| Gradle | 8.13 | **8.14.4** | Boot 4 플러그인이 8.14+ 요구 (설정 캐시 안정 API) |
| `starter-web` | 사용 | **`starter-webmvc`** | 개명 (WebFlux와 이름 대칭) |
| Flyway | `flyway-core` + `flyway-database-postgresql` | **`starter-flyway` + `flyway-database-postgresql`** | 스타터 필수. 아래 5.2 참조 |
| 테스트 starter | `starter-test` | **`starter-webmvc-test` + `starter-data-jpa-test`** | 기술별로 쪼개짐. `starter-test`를 전이로 끌고 온다 |
| Testcontainers | `org.testcontainers:postgresql` | **`testcontainers-postgresql`** | 2.0에서 좌표 전면 개명 |
| Redisson | 3.50.0 | **4.8.0** | 3.50.0은 netty 4.1 요구, Boot 4는 netty 4.2 관리 |
| MyBatis starter | 3.0.4 | **4.1.0** | 4.1.x가 Boot 4.1 대응 라인 |
| springdoc | 2.8.6 | **3.1.1** | 2.x는 Boot 3 전용 |
| `spring-security-test` | 선언됨 | **제거** | 실사용 0건 |
| AssertJ·Mockito | 3.24.2 / 5.8.0 고정 | **고정 해제** | BOM에 맡긴다 (JUnit 버전과 어긋날 수 있다) |

**Gradle 9.8.1(당시 최신) 대신 8.14.4를 골랐다.** Boot 4 최소 요구가 8.14+이고, 9.x로 올리면
뭔가 터질 때 "Gradle 9인가 Boot 4인가"를 분리해야 한다. 원인 특정이 이 Task의 책임이라 변수를
하나 줄였다. 9.x는 별도 건이다.

### 5.2 함정 — `starter-flyway`는 DB 모듈을 안 가져온다

기동이 이렇게 깨졌다.

```
FlywayException: Unsupported Database: PostgreSQL 15.18
```

메시지는 "Flyway가 PostgreSQL 15를 지원하지 않는다"로 읽힌다. **실제 원인은 의존성 누락이다.**
Flyway 10부터 DB별 모듈이 분리됐고 `spring-boot-starter-flyway`는 `flyway-core`까지만 가져온다.

```
spring-boot-starter-flyway:4.1.1
  ├─ spring-boot-starter-jdbc
  └─ spring-boot-flyway:4.1.1
       └─ org.flywaydb:flyway-core:12.4.0      ← 여기서 끝. postgresql 모듈 없음
```

`flyway-database-postgresql`을 명시하니 통과했다 — `Successfully validated 11 migrations`.
Boot 3에서는 두 줄을 같이 썼으니 **"스타터로 합쳐지면서 DB 모듈까지 들어온다"는 추론이 틀린
것**이고, 에러 메시지가 그 추론을 교정해주지 않는다.

### 5.3 Jackson 3 — 필드 순서가 바뀐다

Jackson 2는 **선언 순서**로 직렬화했는데 Jackson 3는 **creator 파라미터를 먼저, 나머지는
알파벳순**이다. 그래서 응답 형식이 조용히 바뀌었다.

| 클래스 | 선언 순서 | Jackson 3 출력 | 원인 |
|---|---|---|---|
| `ApiResponse` | `success, data, code, message, errors` | `{"data":…,"success":true}` | creator 없음(private `@Builder` 생성자) → 전부 알파벳순 |
| `PageResponse` | `content, page, size, …` | `{"page":…,"content":…,"first":…}` | 생성자 파라미터명이 `page`라서 creator 속성으로 인식 → 맨 앞으로 |

**그런데 응답 DTO 17개는 안 바뀌었다.** 전부 record라 컴포넌트 순서가 정의로 고정돼 있다.
순서를 라이브러리 기본값에 맡기던 클래스가 **딱 2개**였고, 그게 공통 래퍼 2개였다.

```java
@JsonPropertyOrder({"success", "data", "code", "message", "errors"})
public class ApiResponse<T> { ... }
```

| 선택지 | 판단 |
|---|---|
| 전역으로 `SORT_PROPERTIES_ALPHABETICALLY` 끄기 | ❌ "선언 순서에 암묵적으로 의존"하는 상태로 되돌아간다. 그게 방금 우리를 문 그 의존이다 |
| **2개 클래스에 `@JsonPropertyOrder` 명시** | ✅ 채택. 이제 **어떤 DTO도 순서가 라이브러리 기본값에 안 걸린다** |

기능적으로는 FE가 키로 읽으니 무해하다. 고친 이유는 `ApiResponse`의 Javadoc이
`{success, data, code, message, errors}`를 계약으로 적어둔 상태라 **문서가 거짓이 되는 것**이다.

### 5.4 패키지·API 이동

| 변경 | 파일 수 | 컴파일 에러? |
|---|---|---|
| `@AutoConfigureMockMvc`: `boot.test.autoconfigure.web.servlet` → **`boot.webmvc.test.autoconfigure`** | 5 | ✅ 에러 10개. **유일하게 컴파일러가 잡아준 것** |
| `ObjectMapper` → `JsonMapper` (`tools.jackson.databind.json`) | 6 | ❌ 통과 (4.1) |
| `PostgreSQLContainer` 패키지 + **제네릭 소멸** (`<?>` → 없음) | 1 | ❌ 통과 (4.2) |
| `findValuesAsText()` → **`findValuesAsString()`** | 1 | ✅ 에러 1개 |
| `asText()` → **`asString()`** | 2 | ❌ deprecated 경고만 (4.3) |

**Testcontainers 변경이 1파일에 갇혔다.** 테스트 11개가 `AbstractContainerTest`를 import하고
Testcontainers를 직접 import하는 건 그 베이스 클래스뿐이다. Task 002에서 "공통 베이스 테스트
클래스"를 만든 결정이 라이브러리 메이저 업그레이드 비용을 1파일로 묶어줬다.

## 6. Hibernate 7 관문 — 통과

조건부 UPDATE 7개의 영향 행 수 반환이 **Boot 3.4와 정확히 동일**하다.

| 쿼리 | 파일 | 종류 | 반환값 의미 |
|---|---|---|---|
| `holdSeats` | `ScheduleSeatRepository` | native, 다중행 CTE | 바깥 **INSERT** 행 수 |
| `markSold` / `releaseSeats` | 같은 파일 | native | UPDATE 행 수 |
| `expirePendingReservations` | `ReservationRepository` | native, 데이터 변경 CTE | **반환된 좌석 수** (만료 예약 건수가 아니다) |
| `confirmIfPending` / `cancelIfStatus` | 같은 파일 | **JPQL** 조건부 UPDATE | 0이면 전이 실패 |
| `recalculateDerivedFields` | `PerformanceRepository` | native | UPDATE 행 수 |
| `nextReservationNoSeq` | `ReservationRepository` | native 스칼라 | `nextval` → `long` |

`ReservationConcurrencyTest` 5개가 찍은 값:

| 시나리오 | before (Boot 3.4) | after (Boot 4.1.1) |
|---|---|---|
| 초과 판매 차단 (10석·100명) | 201 **10** / 409 **90** | 201 **10** / 409 **90**, 그 외 0 · HELD 10 / AVAILABLE 0 |
| 중복 선점 차단 (같은 회원·20명) | 201 **1** / 409 **19** | 201 **1** / 409 **19** · PENDING 1건 |
| 겹치는 좌석 집합 | 1건 · 데드락 0 · 부분 선점 0 | 201 **1** / 409 **20** · 점유 3석 / 총 6석 |
| 좌석당 한 명 (1석·100명) | 1건 | 201 **1** / 409 **99** |
| 결제-만료 경쟁 + 보상 환불 | EXPIRED + 409 + 재고 복원 | 좌석 1석 반환 · 409 · EXPIRED · **결제 없음** · 10/10석 |

`@Modifying(flushAutomatically = true, clearAutomatically = true)`의 flush 시점도 유지된다 —
안 그랬으면 초과 판매가 10건보다 많이 나왔을 것이다.

### 왜 안 깨졌나

**조건부 UPDATE를 전부 원시 SQL/JPQL로 직접 쓰고 ORM의 상태 관리를 안 믿도록 설계했기
때문이다.** Task 019가 엔티티에서 상태 변경 메서드를 **삭제**해 더티체킹 경로를 없앤 결정이
결과적으로 **Hibernate 변경에 대한 노출 면적을 줄여놨다.**

```java
// ReservationService.java — Task 019의 주석이 이 Task에서 왜 중요한지 보여준다
// 엔티티 필드를 건드리지 않는 것이 중요하다 — 영속 인스턴스가 더티가 되면 flush 시점에
// Hibernate가 메모리의 낡은 값으로 UPDATE를 또 발행해 이 조건부 UPDATE를 덮어쓴다.
int held = scheduleSeatRepository.holdSeats(...);
if (held != seatIds.size()) throw new BusinessException(ErrorCode.SOLD_OUT);
```

Hibernate 7이 실제로 바꾼 것(native 쿼리 날짜 타입, `@Immutable` 벌크 UPDATE 예외)은 우리가
쓰지 않는 기능이고, 우리가 의존하는 것(`executeUpdate()` 행 수)은 JDBC 계약에 가까워서
건드려지지 않았다. 3절의 `@MockBean` 이야기와 같은 패턴이다 — **ORM/프레임워크 기능을 적게 쓴
쪽이 업그레이드에서 유리했다.**

## 7. 수치 — API 계약과 성능

| 측정 | before (Task 022) | after | 판정 |
|---|---|---|---|
| 좌석맵 응답 크기 (3,200석) | 408,705 bytes | **408,705 bytes** | **바이트 단위 동일** |
| 좌석맵 쿼리 플랜 | `Seq Scan on seats`(7,120) + `quicksort 397kB` + `uk_schedule_seats_schedule_seat` | **동일** | 플랜 퇴화 없음 |
| 등급별 예매 가능 좌석 COUNT | 0.82 ms | 0.67~0.76 ms | 동일 수준 |
| `schedule_seats` HOT | **100%** | **100%** (3/3) | 유지 |
| `reservations` HOT | 0% | **0%** (0/2) | 유지 |
| 테스트 | 123개 통과 | **123개 통과** (실패 0 / 에러 0 / 건너뜀 0) | 하나도 잃지 않음 |
| e2e | 3개 통과 | **3개 통과** (6.0s) | FE 무수정 |
| 기동 시간 | 미측정 | 4.42초 | 신규 |

신규 측정(before 없음): 좌석맵 API 왕복 15~17 ms, 잔여 수량 API 6~7 ms, 공연 목록 API 4~5 ms.

**408,705 bytes가 바이트 단위로 일치한 게 가장 값진 수치다.** 좌석 3,200개를 직렬화한 결과가
1바이트도 안 달라졌다는 건 Jackson 2 → 3 메이저 교체에도 필드명·필드 순서·숫자 포맷·null 처리가
전부 같다는 뜻이다. 5.3의 `@JsonPropertyOrder`가 여기서 검증된다.

### HOT 비율 표의 두 줄이 서로를 설명한다

`schedule_seats` 100% / `reservations` 0%. Task 021이 `schedule_seats`에만 `fillfactor = 90`을
걸고 인덱스를 일부러 안 걸었던 이유다 — HOT의 전제가 둘이다: (1) 페이지에 빈 공간,
(2) 갱신 컬럼에 인덱스 없음. `reservations`는 `uk_reservations_reservation_no` 등이 있어
`status` UPDATE마다 인덱스 엔트리를 다시 써야 한다.

업그레이드 검증에서 이게 의미 있는 건 **스토리지 레이어 동작은 Hibernate가 아니라 Postgres와
스키마가 결정한다**는 확인이다. 흔들렸다면 Hibernate가 UPDATE 문을 다르게(예: 전체 컬럼 UPDATE로)
발행했다는 신호였을 것이다.

## 8. 새로 생긴 경고

| 경고 | 평가 |
|---|---|
| `SpringDoc /swagger-ui.html endpoint is enabled by default...` | **신규** (springdoc 3.x). 운영에서 끄려면 `springdoc.swagger-ui.enabled=false` → **Task 024에서 처리** |
| `spring.jpa.open-in-view is enabled by default` | 기존부터 있던 경고 |
| `No MyBatis mapper was found in '[com.tikkit]'` | 기존 (매퍼 아직 없음) |
| `org.hibernate.orm.jdbc.error: duplicate key ... uk_reservations_pending_member_grade` 등 | **의도된 것** — 제약 위반을 단정하는 테스트(V3_2 부분 유니크 인덱스, 복합 FK, CHECK)가 찍는 로그 |

deprecation 경고는 최종 0건이다.

## 9. 한계

- **HOT 측정 표본이 3건이다.** Task 022도 6건이었고 같은 한계다 — 경향만 본 것이고 절대 비율로
  읽을 수 없다. Task 027 부하 테스트에서 다시 재야 한다.
- **좌석맵 DB 쿼리 3.15~3.55 ms vs before 10.70 ms를 "빨라졌다"로 읽으면 안 된다.** 측정에 쓴
  SQL은 Hibernate가 실제 발행하는 것이 아니라 **004 문서의 플랜 설명을 보고 재구성한 동등
  쿼리**이고 캐시 상태도 다르다. 유효한 결론은 "플랜 모양이 같다"까지다.
- **Jackson 2와 3이 한 JVM에 공존한다** (4절 콜아웃). swagger-core가 Jackson 2를 쓰는 동안
  그대로다.
- **한 번에 올렸으므로 "무엇이 무엇을 깨뜨렸나"를 diff로 가를 수 없다.** 3.5.x를 경유하면
  deprecation을 먼저 걷어낼 수 있었지만 커밋이 2개로 늘고, 조사로 파괴적 변경을 이미 특정했기에
  직행을 골랐다. 결과적으로 터진 건 Flyway 모듈 누락 하나였고 원인이 명확해서 비용을 치르지
  않았다 — **다음 메이저에서도 통할 보장은 없다.**
- **Gradle은 8.14.4(8.x 마지막)에 머물러 있다.** 9.x 전환은 별도 건이다.
- 측정은 전부 로컬 Docker 기준이라 **방식 간 상대 비교**로만 읽어야 한다 (002·003·004와 같은 단서).

## 10. 다음 단계

- [Task 024: 컨테이너화](../ROADMAP.md) — Dockerfile base image를 Boot 4 기준으로 한 번만 쓴다.
  8절의 springdoc 운영 비활성화도 여기서 처리한다.
- [Task 025: 모니터링](../ROADMAP.md) — Actuator 엔드포인트와 Micrometer 지표명이 Boot 4
  기준으로 정해진다.
- [Task 027: 부하 테스트](../ROADMAP.md) — HOT 비율(표본 3건)과 좌석맵 조회를 약 600만 행에서
  다시 잰다.

## 재현 방법

```bash
cd tikkit-back

# 1. 버전 확인
./gradlew --version                   # Gradle 8.14.4
./gradlew dependencies --configuration runtimeClasspath \
  | grep -E "netty-common|hibernate-core|jackson-databind|flyway-core|querydsl-jpa"
#   -> netty 4.2.17 단일 해석 / hibernate 7.4.5 / jackson 3.1.5 + 2.21.5 공존 / flyway 12.4.0

# 2. Jackson 2를 끌고 오는 출처 (4.1절)
./gradlew dependencyInsight --configuration runtimeClasspath \
  --dependency com.fasterxml.jackson.core:jackson-databind

# 3. deprecation 경고 (4.3절) — allprojects로 감싸야 한다
cat > /tmp/dep.gradle <<'EOF'
allprojects {
    tasks.withType(JavaCompile).configureEach {
        options.compilerArgs << "-Xlint:deprecation"
    }
}
EOF
./gradlew compileJava compileTestJava --rerun-tasks -I /tmp/dep.gradle
#   -> 현재 0건. asText()를 되돌리면 3건이 나온다

# 4. 전체 테스트 (6절)
docker compose up -d && ./gradlew test        # 123개
./gradlew test --tests '*ReservationConcurrencyTest*'
#   -> [초과 판매 차단] 201 10건 / 409 90건 등이 로그에 찍힌다

# 5. 기동과 API 계약 (7절)
./gradlew bootRun
curl -s http://localhost:8080/api/v1/schedules/17/seats -o /dev/null -w "%{size_download} bytes\n"
#   -> 408705 bytes
curl -s http://localhost:8080/api/v1/members/me
#   -> {"success":false,"code":"UNAUTHORIZED",...}  순서까지 확인 (5.3절)

# 6. HOT 비율 (7절) — 통계를 리셋한 뒤 e2e를 한 번 돌린다
docker exec tikkit-postgres psql -U tikkit_user -d tikkit_db -c "
SELECT pg_stat_reset_single_table_counters('schedule_seats'::regclass);"
cd ../tikkit-front && npm run build && npm run test:e2e      # 3개 통과
docker exec tikkit-postgres psql -U tikkit_user -d tikkit_db -c "
SELECT relname, n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / nullif(n_tup_upd, 0), 1) AS hot_pct
FROM pg_stat_user_tables WHERE relname IN ('schedule_seats', 'reservations');"
```
