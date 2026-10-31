# 007. 쿼리·인덱스 최적화 — 버퍼를 88% 줄였는데 아무것도 빨라지지 않았다 (Task 028)

| 항목 | 내용 |
|---|---|
| 관련 Task | Task 028 (Phase 8: 성능 개선) |
| 대상 코드 | `ReservationService.createPending`, `TicketGradeRepository{Custom,Impl}`, `QueryCounter`(신규), `*QueryCountTest`(신규) |
| 작성 시점 | 2026-10-14 |
| before 기준선 | [006. 성능 베이스라인](006-performance-baseline.md) 7.1절 — 탐색 p95 2.36ms / 좌석맵 6.72ms / 예매 7.43ms (keepalive ON, 600만 행, 3회 중앙값) |
| 결론 | **인덱스를 하나도 추가하지 않았다 — `V7__add_indexes.sql`을 만들지 않았다.** 후보 7종을 실측했고 전부 기각됐다. 대신 쿼리 카운트 하네스를 만들어 예매 선점의 왕복을 **7 → 6**으로 줄이고, N+1이 없는 상태를 테스트로 고정했다 |
| 알게 된 것 | `Buffers: shared hit`은 I/O의 대리지표가 아니다. 버퍼 접근 2,130 → 354(-83%)인 두 플랜의 실제 페이지 읽기는 196 vs 198로 같았다 |
| 데이터 | `schedule_seats` 6,026,894행 (heap 599MB), DB 1,039MB, `shared_buffers` 128MB |

---

## 1. 왜 만들었나

006이 측정 도구와 기준선을 만들고 Task 028로 세 가지를 넘겼다.

> N+1 탐지는 지표가 아니라 SQL 로그/쿼리 카운트로 해야 한다. 9절의 "데이터가 균일하다"를
> `EXPLAIN ANALYZE` 해석에서 반드시 감안한다.
> — [006](006-performance-baseline.md) 10절

> `schedule_seats.reservation_id` 인덱스를 추가하면 HOT 업데이트가 깨지므로(V4의 설계 결정)
> **정답은 "삭제하지 않는다"일 수 있다. Task 028에서 인덱스를 손댈 때 같이 판단한다.**
> — [006](006-performance-baseline.md) 10절

그리고 ERD가 V7의 타깃을 미리 지목해뒀다.

> 오히려 비싼 쪽은 좌석맵 조회(3,200석)로 **10.70 ms**였고, 원인은 `schedule_seats`가 아니라
> `seats` 전체 Seq Scan(7,120행)과 3,200행 정렬이다. 그래서 V7에서 볼 것은
> `idx(schedule_id, status)`가 아니라 좌석맵의 조인·정렬 전략이다.
> — `docs/ERD.md` 2절 (Task 022 실측)

이 Task의 범위를 "측정 근거가 붙은 변경만"으로 잡았다. 인덱스를 추가하지 않는 결론도 결과로
받아들인다 — Task 022가 이미 한 번 그렇게 끝났고, 그때 남긴 수치가 이번 측정의 출발점이 됐다.

## 2. 측정 환경

006 7.1과 같은 조건으로 맞췄다. 조건이 하나라도 다르면 before/after를 비교할 수 없다.

| 항목 | 값 |
|---|---|
| 스택 | `docker-compose.prod.yml` (nginx → BE 2대, keepalive ON) |
| 데이터 | `load-test/sql/01~03` 주입분 그대로 — `schedule_seats` **6,026,894행** |
| Postgres | 15, `shared_buffers` **128MB**, `effective_cache_size` 4GB |
| k6 | 2.3.0, `--no-deps`, 시나리오 3종 × 3회 중앙값, 측정 사이 `98-reset-reservations.sql` |
| EXPLAIN | `EXPLAIN (ANALYZE, BUFFERS)`, 같은 쿼리를 회차만 바꿔 3회 |

**SQL은 재구성하지 않았다.** 004 문서가 "플랜 설명을 보고 재구성한 동등 쿼리"를 쓰다가
"before와 직접 비교할 수 없다"는 한계를 남겼다. 이번에는 test 프로필(`show-sql: true`)에서
Hibernate가 실제로 생성한 SQL을 그대로 받아 적고, 그 문장을 600만 행 DB에 던졌다.

## 3. 쿼리 카운트 하네스 — 처음부터 만들었다

`src/test` 전체에 `Statistics`·p6spy·datasource-proxy가 한 건도 없었다. 외부 의존성을 더하지 않고
Hibernate가 이미 들고 있는 통계를 쓴다.

```java
public QueryCounter(EntityManagerFactory entityManagerFactory) {
    this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    if (!statistics.isStatisticsEnabled()) {
        throw new IllegalStateException(
                "hibernate.generate_statistics가 꺼져 있어 쿼리 수를 셀 수 없다. application-test.yml을 확인한다.");
    }
}
```

`generate_statistics`는 `application-test.yml`에만 켠다. 꺼져 있으면 카운트가 조용히 0으로 나와
"쿼리가 한 건도 안 나갔다"는 통과하는 거짓 테스트가 되므로, 생성자에서 끊는다.

### 3.1 트랜잭션 밖에서 세야 한다

처음에 기존 통합 테스트처럼 `@Transactional`을 붙이려 했는데, 그러면 세는 값이 운영과 달라진다.
세션 하나가 테스트 끝까지 열려 있어서 두 번째 조회가 1차 캐시에 맞고, flush 시점도 요청 경계와
어긋난다. 그래서 **`@Transactional`을 쓰지 않는 베이스**가 필요했고, 이미 있었다 —
`AbstractConcurrencyTest`(Task 018)가 비트랜잭션 + `truncateAll()` + 픽스처를 갖추고 있다.
동시성과 무관하지만 필요한 조건이 정확히 일치해서 그대로 상속했다.

### 3.2 조건부 UPDATE의 flush가 섞인다

선점·확정·취소 쿼리가 모두 `@Modifying(flushAutomatically = true)`다. 호출마다 flush가 끼어들기
때문에, 늘어난 문장을 N+1로 오독하지 않도록 경로별 구성을 주석에 적어 고정했다.

## 4. 핵심 발견

### 4.1 N+1이 없다 — 구조가 막고 있었다

전수 조사 결과 **`@OneToMany`가 전 엔티티에 0건, `@BatchSize`도 0건, `@EntityGraph`·JPQL
`join fetch`도 0건**이다. 모든 연관이 단방향 `@ManyToOne(LAZY)`이고 목록 응답은 전부
`Projections.constructor` DTO 프로젝션이다.

N+1은 컬렉션을 로딩할 때 생기는 문제다. 컬렉션 연관이 아예 없으면 생길 자리가 없다. 그래서
ROADMAP이 적어둔 "`@BatchSize`로 해결"은 **적용할 지점이 없었다** — 쓰려면 먼저 `@OneToMany`를
추가해야 하고, 그건 N+1을 만들어 고치는 일이다.

그래서 이 Task의 N+1 파트는 "고치기"가 아니라 **"없는 상태를 고정하기"**로 바꿨다. 나중에 누가
엔티티를 그대로 내보내거나 응답 DTO 안에서 연관을 당겨 쓰면 숫자가 늘어나면서 테스트가 깨진다.

### 4.2 대신 쓰기 경로에서 왕복 1회를 찾았다 — 7 → 6

유일하게 찾은 지연 로딩이다. `ReservationService.createPending`:

```java
TicketGrade ticketGrade = ticketGradeRepository.findById(request.ticketGradeId())...  // SELECT ticket_grades
Schedule schedule = ticketGrade.getSchedule();       // 프록시 — 쿼리 없음
if (!schedule.getId().equals(request.scheduleId()))  // getId()도 쿼리 없음
if (!schedule.isBookingOpen(now))                    // ← bookingOpenAt 접근 → SELECT schedules
```

`getId()`까지는 프록시가 버티는데 `bookingOpenAt`을 읽는 순간 초기화된다. 등급을 회차와 함께
fetch join으로 받아 없앴다.

```java
// 회차를 fetch join으로 함께 받는다. findById로 등급만 읽으면 바로 아래 isBookingOpen에서
// Schedule 프록시가 초기화되면서 SELECT가 한 번 더 나간다 (Task 028, 왕복 7 -> 6).
TicketGrade ticketGrade = ticketGradeRepository.findByIdWithSchedule(request.ticketGradeId())
```

조회가 아니라 **쓰기 경로**라서 캐싱(Task 029)으로 가릴 수 없는 왕복이다. 쿼리 카운트 테스트가
before 7 / after 6을 그대로 기록한다.

### 4.3 목록이 2문장이 아니라 1문장이었다

"목록은 content + count 2문장"으로 단정했는데 **1문장**이 나와서 테스트가 깨졌다.
`PageableExecutionUtils.getPage`가 offset 0이고 content가 페이지 크기보다 작으면 전체 건수를
content 크기로 확정할 수 있어서 **count 쿼리를 아예 안 쏜다.**

틀린 쪽은 코드가 아니라 내 예상이었다. 테스트를 두 개로 갈라 양쪽을 다 고정했다.

| 호출 | 문장 수 |
|---|---|
| `list(size=20)` — 첫 페이지가 안 찬다 | **1** (count 생략) |
| `list(size=1)` — 페이지가 찬다 | **2** |

### 4.4 ERD의 예측이 절반만 맞았다 — 정렬은 병목이 아니다

좌석맵(회차 21, 2,000석) 첫 측정:

```
 Sort (actual time=4.393..4.443 rows=2000)        ← 정렬 자체는 0.05ms
   Sort Method: quicksort  Memory: 220kB
   ->  Hash Join (actual time=1.869..3.596 rows=2000)
         ->  Seq Scan on seats (actual time=0.018..0.579 rows=9120)   ← 0.58ms
         ->  Hash (actual time=1.836..1.841 rows=2000)
               ->  Index Scan using uk_schedule_seats_schedule_seat (actual time=0.052..1.514 rows=2000)
                     Buffers: shared hit=2880                          ← 행당 1.4 버퍼
 Planning Time: 2.056 ms
 Execution Time: 4.604 ms
```

ERD가 지목한 두 범인 중 **`seats` Seq Scan은 맞고(0.58ms) 정렬은 틀렸다(0.05ms)**.
4.39ms를 쓴 건 그 앞의 Hash Join과 `schedule_seats` Index Scan이고, 그 Index Scan이
버퍼 2,880개를 찍는 이유는 인덱스에 `id`·`status`·`ticket_grade_id`가 없어서 행마다 힙을
다시 보기 때문이다.

그래서 후보가 "정렬용 인덱스"가 아니라 **커버링 인덱스**로 바뀌었다. 그리고 커버링 인덱스는
`status`를 인덱스에 넣는다는 뜻이라, 정확히 V4가 HOT 업데이트를 지키려고 피한 선택이다.

### 4.5 버퍼를 83% 줄였는데 아무것도 빨라지지 않았다

```sql
CREATE INDEX idx_ss_cover ON schedule_seats (schedule_id)
    INCLUDE (seat_id, status, ticket_grade_id);
```

같은 조건(`VACUUM (ANALYZE, PARALLEL 0)` 후, 회차 3개를 번갈아 3회)으로 A/B를 쟀다.

| 쿼리 | A: 인덱스 없음 | B: 커버링 인덱스 | 차이 |
|---|---|---|---|
| 좌석맵 (2,000석) | 2.816 / **2.423** / 2.283 ms | 2.729 / **2.469** / 2.092 ms | **없음** |
| 좌석맵 버퍼 | shared hit **2,130** | shared hit **354** | -83% |
| 등급 AVAILABLE COUNT | 0.607 / **0.579** / 0.466 ms | 0.458 / **0.491** / 0.517 ms | -0.09ms (산포 안) |
| 등급 COUNT 버퍼 | shared hit **1,969** | shared hit **193** | -90% |
| 인덱스 크기 | — | **338MB** (heap 599MB의 56%) | |
| 인덱스 생성 시간 | — | **3.07초** (ACCESS EXCLUSIVE) | |

**버퍼는 6배 줄었는데 시간은 그대로다.** 첫 측정의 4.604ms와 비교하면 "-30% 개선"이라는 결론이
나왔을 텐데, 그 4.604ms에는 cold 상태와 planning 2.056ms가 섞여 있었다. 조건을 맞추니 차이가
사라졌다. 006이 "측정 도구가 틀리면 개선도 틀린다"고 적은 함정에 그대로 빠질 수 있었다.

### 4.6 `Buffers: shared hit`은 I/O의 대리지표가 아니다

버퍼가 83% 줄었는데 왜 안 빨라졌나. shared buffers를 비우고(컨테이너 재시작) 한 번도 안 건드린
회차로 cold 측정을 해봤다.

| | A: 인덱스 없음 (회차 500) | B: 커버링 인덱스 (회차 900) |
|---|---|---|
| 실행 시간 | 4.757 ms | 4.015 ms |
| `shared read` (실제 페이지 읽기) | **196** | **198** |
| 그중 `seats` Seq Scan | 154 | 154 |
| 그중 `schedule_seats` | 42 | 44 |

**실제로 디스크에서 읽은 페이지 수가 같다.** `02-bulk-schedule-seats.sql`이 회차 단위로
INSERT하기 때문에 한 회차의 2,000행이 물리적으로 42페이지에 뭉쳐 있다. 힙을 안 보면 그 42페이지를
안 읽는 게 아니라, 인덱스 페이지 44개를 대신 읽는다.

`shared hit=2880`은 "페이지 2,880개를 읽었다"가 아니라 **"버퍼를 2,880번 집었다"**다. 같은
페이지를 49번 집으면 49로 센다. 커버링 인덱스는 그 집는 횟수를 줄였을 뿐이고, 그건 메모리 안에서
벌어지는 일이라 비용이 거의 없다.

그래서 기각했다. **338MB와 HOT 업데이트를 내주고 얻는 게 0.5ms 미만**이다.

### 4.7 offset 980에서 플랜이 뒤집힌다 — 둘 다 0.55ms

탐색 시나리오는 50페이지를 무작위로 넘긴다(`browse.js`). 첫 페이지와 마지막 페이지의 플랜이
다르다.

| | page 0 (offset 0) | page 49 (offset 980) |
|---|---|---|
| 플랜 | `idx_performances_status_start_date` Index Scan + **Incremental Sort** | **Seq Scan** + full Sort (quicksort 166kB) |
| venue 조인 | Memoize (hits 999 / misses 4) | Hash Join |
| 실행 시간 | **0.551 ms** | **0.556 ms** |

높은 offset에서는 어차피 1,000행을 다 읽어야 하므로 플래너가 인덱스를 버린다. 합리적인 선택이고,
**두 플랜의 시간이 사실상 같다.** 1,008행짜리 테이블에서 인덱스 유무는 의미가 없다.

`venues`가 5행뿐이라 Memoize가 999/1,003을 캐시로 받는다 — `performances.venue_id` 인덱스를
후보에서 뺀 근거다.

### 4.8 키워드 검색은 Seq Scan인데 1.3ms다

```
 ->  Seq Scan on performances (actual time=0.050..0.457 rows=1000)
       Filter: (((status)::text = 'ON_SALE') AND (lower((title)::text) ~~ '%부하테스트%'))
       Rows Removed by Filter: 8
 ->  Index Scan using venues_pkey on venues (loops=1000)   ← 버퍼 2,000
 Execution Time: 1.329 ms
```

`containsIgnoreCase`는 `lower(title) like '%kw%'`로 나가서 어떤 B-tree도 못 탄다. ERD가
"`pg_trgm` 기반 키워드 검색은 Task 028에서 `EXPLAIN ANALYZE` 근거로 결정한다"고 예약해뒀는데,
**1,008행에서 1.3ms라 근거가 안 나온다.** 전체 1,000행이 매칭되는 최악의 패턴이고 그래도 이 값이다.

도입하지 않았다. ERD가 같이 적어둔 "3자 미만 패턴에는 인덱스를 타지 않아 2음절 한글 제목에 한계"도
그대로 유효하므로, 공연 수가 수십만이 될 때 다시 판단할 항목으로 남긴다.

### 4.9 `reservation_id` 인덱스 — 9,900배를 얻는데 기각했다

006이 넘긴 숙제다. 먼저 **얻는 쪽**을 쟀다. 예약 100건을 물리 삭제한다(`ROLLBACK`으로 되돌림).

| | 실행 시간 | 건당 |
|---|---|---|
| 인덱스 없음 | **26,906 ms** | 269 ms |
| `idx_schedule_seats_reservation_id` | **2.71 ms** | 0.027 ms |

**약 9,900배다.** 006이 "13,798건을 지우려다 12분 넘겨 중단했다"고 한 것과 계산이 맞는다
(13,798 × 0.269초 ≈ 62분). 인덱스는 40MB, 생성은 1.09초.

이제 **내주는 쪽**. booking-spike를 2회 돌려 HOT 비율과 선점 p95를 쟀다.

| | HOT 비율 (`schedule_seats`) | 예매 p95 |
|---|---|---|
| 인덱스 없음 (A군, 3회) | **100.0% / 100.0% / 100.0%** | 7.51 / **7.77** / 7.83 ms |
| 인덱스 있음 (C군, 2회) | **34.1% / 35.2%** | **8.51 / 8.50 ms** |

HOT이 100% → **34%**로 떨어지고 예매 p95가 **+0.74ms(+9.5%)** 느려진다.

34%라는 숫자가 0이 아닌 게 재밌다. 좌석을 건드리는 UPDATE가 세 종류인데 그중 하나만 살아남는다.

| UPDATE | `reservation_id`를 바꾸나 | HOT |
|---|---|---|
| `holdSeats` (선점) | 바꾼다 (NULL → 예약 id) | ✗ |
| `markSold` (결제 확정) | **안 바꾼다** (`status`만) | **O** |
| `releaseSeats` (취소·만료) | 바꾼다 (예약 id → NULL) | ✗ |

3분의 1이 HOT으로 남아서 34%다. 인덱스가 깨는 건 "모든 UPDATE"가 아니라 "그 컬럼을 바꾸는 UPDATE"다.

**기각했다.** 9,900배가 커 보이지만 **그 경로는 서비스에 존재하지 않는다.** 예약 상태 머신에는
물리 삭제가 없고(`CANCELLED`/`EXPIRED`로 남긴다), 부하 테스트 리셋도 이미 `reservation_seats`를
경유해 0.73초로 해결돼 있다. 반대로 HOT 파괴와 +0.74ms는 **모든 선점·취소 요청이 매번** 무는 비용이다.

측정값의 크기가 채택 근거가 아니라, **그 경로가 실제로 도는지**가 근거다. 그래서 ERD에
"운영에서 예약을 물리 삭제하지 않는다"를 명시하는 쪽으로 닫았다.

### 4.10 `uk_members_email`은 구조적으로 쓰일 수 없다

범위 밖에서 발견했다. 인덱스는 **함수 인덱스**인데,

```sql
CREATE UNIQUE INDEX uk_members_email ON members (lower(email));   -- V1
```

리포지토리는 평문 컬럼으로 조회한다.

```java
Optional<Member> findByEmail(String email);   // -> where email = ?
```

Postgres는 함수 인덱스를 평문 컬럼 조건에 쓸 수 없다. **로그인이 이 인덱스를 한 번도 타지 않는다.**

| 조건 | 플랜 | 실행 시간 (warm 3회) |
|---|---|---|
| `where email = ?` (현재 코드) | **Seq Scan** 503행 | 0.091 / **0.088** / 0.082 ms |
| `where lower(email) = ?` | Index Scan | 0.046 / **0.049** / 0.055 ms |

**고치지 않았다.** 차이가 0.039ms인데 로그인 p95는 BCrypt 때문에 54ms다(006 4.3절) — 0.07%다.
인증 경로의 쿼리 의미를 그 이득으로 바꾸는 건 이 Task가 요청받은 범위가 아니다. 회원이 503명이
아니라 수십만이 되면 Seq Scan이 선형으로 늘어나므로, 그때 판단할 항목으로 10절에 남긴다.

---

## 5. 실제로 만든 것

### 5.1 쿼리 카운트 하네스와 테스트 10개

`QueryCounter`(27줄)와 테스트 두 클래스. 경로별 문장 수를 숫자로 단정한다.

| 경로 | 문장 수 | 구성 |
|---|---|---|
| `GET /performances` (size 20) | 1 | content (count 생략) |
| `GET /performances` (size 1) | 2 | content + count |
| `GET /performances/{id}` | 2 | 공연+공연장 fetch join / 회차 목록 |
| `GET /schedules/{id}/ticket-grades` | 3 | 회차 존재 확인 / 등급 행 / AVAILABLE COUNT |
| `GET /schedules/{id}/seats` | 2 | 회차 존재 확인 / 좌석맵 (좌석 수와 무관) |
| `POST /reservations` | **6** (before 7) | 4.2절 |
| `GET /reservations` (size 20) | 1 | content (count 생략) |
| `GET /reservations/{id}` | 3 | 본문 fetch join / 결제 / 좌석 |

좌석 수·결과 행 수와 무관하다는 것도 같이 단정한다 — 선점을 루프로 돌리거나 좌석마다 `seat`를
당겨 쓰면 그 테스트가 먼저 깨진다.

### 5.2 등급 조회에 fetch join 쿼리 하나 추가

`TicketGradeRepositoryCustom.findByIdWithSchedule`. 4.2절의 왕복 1회를 없애는 용도이고,
회차를 함께 받아도 호출부는 판매 기간 확인만 한다 — `ReservationService`가 "엔티티 필드를 고치면
안 된다"(더티체킹이 조건부 UPDATE를 덮어쓴다)고 못 박아둔 규칙을 그대로 지킨다.

### 5.3 기각한 것 — 인덱스를 하나도 추가하지 않았다

`V7__add_indexes.sql`을 만들지 않았다. 후보 전부에 근거가 없다.

| 후보 | 기각 근거 |
|---|---|
| `schedule_seats (schedule_id) INCLUDE (seat_id, status, ticket_grade_id)` | 실행 시간 차이 없음, 실제 페이지 읽기 196 vs 198. 대가는 338MB + HOT 파괴 (4.5·4.6절) |
| `schedule_seats (schedule_id, status)` | 위와 같은 기전. 등급 COUNT가 이미 0.58ms이고 `uk_schedule_seats_schedule_seat`의 선두 컬럼으로 회차가 좁혀진다 |
| `performances (venue_id)` | `venues`가 5행이라 Memoize가 999/1,003을 캐시로 받는다 (4.7절) |
| `performances` 목록용 복합 인덱스 | page 0은 이미 기존 인덱스를 타고, page 49는 플래너가 Seq Scan을 고르는데 **둘 다 0.55ms** (4.7절) |
| `pg_trgm` GIN (제목 검색) | 최악 패턴(1,000행 전부 매칭)에서 1.33ms (4.8절) |
| `schedule_seats (reservation_id)` | 예약 물리 삭제가 9,900배 빨라지지만 **그 경로가 서비스에 없다**. 대가는 HOT 100% → 34%, 예매 p95 +9.5% (4.9절) |
| `members (email)` 평문 인덱스 | 기존 함수 인덱스와 중복이고, Seq Scan과의 차이가 0.039ms다 (4.10절) |
| `reservations (schedule_id)` / `reservation_seats (schedule_seat_id)` | 그 방향으로 조회하는 쿼리가 코드에 없다 |

Task 022가 "인덱스를 추가할 근거가 없었다"로 끝낸 것과 같은 결론이다. 다른 점은 그때는 dev 규모
26,894행이었고 이번은 **600만 행에서도 같다**는 것이다.

## 6. 관문 — 측정을 시작조차 못 했다: Git Bash가 컨테이너 경로를 번역한다

006의 재현 커맨드를 그대로 붙였는데 k6가 이렇게 죽었다.

```
level=error msg="The moduleSpecifier \"C:/Program Files/Git/scripts/booking-spike.js\"
couldn't be found on local disk."
```

`/scripts/booking-spike.js`는 **컨테이너 안의 경로**인데, Git Bash(MSYS2)가 `/`로 시작하는 인자를
Windows 경로로 번역해서 `C:/Program Files/Git/scripts/...`로 바꿔 넘긴다. `--summary-export=/results/...`도
같이 망가진다. 006을 작성할 때는 PowerShell로 돌렸던 것으로 보이고, 그래서 저장소의 재현 커맨드는
셸에 따라 **그냥 안 된다**.

```bash
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
```

두 변수를 export하면 번역이 꺼진다. 아래 재현 방법에 넣었다.

## 7. 수치

### 7.1 k6 시나리오 — before/after (3회 중앙값, keepalive ON, 600만 행)

| 시나리오 | before (006) | after (028) | 차이 |
|---|---|---|---|
| 탐색 p95 | **2.36 ms** | 2.32 / **2.48** / 2.52 ms | +0.12 ms |
| 좌석맵 p95 | **6.72 ms** | 6.67 / **6.77** / 6.93 ms | +0.05 ms |
| 예매 p95 | **7.43 ms** | 7.51 / **7.77** / 7.83 ms | +0.34 ms |
| 예매 처리량 | 82.2 req/s | **82.2 req/s** | — |
| 좌석맵 응답 | 237.9 KB / 2,000석 | **237.9 KB / 2,000석** | — |
| `tikkit_real_errors` | 0.00% | **0.00%** | — |
| checks | 100% | **100%** | — |

**개선이 안 보인다. 그게 예상된 결과였다.**

> 매 iteration마다 로그인하는 스크립트를 썼다면 전체 p95가 로그인 하나에 끌려갔을 것이고,
> **Task 028에서 쿼리를 2ms 개선해도 그 변화가 보이지 않는다.**
> — [006](006-performance-baseline.md) 4.3절

선점에서 줄인 건 2ms가 아니라 쿼리 1문장(약 0.1~0.3ms)이고, 그 값은 3회 산포(0.32ms)보다 작다.
**이 변경은 p95로 검증할 수 없고, 쿼리 카운트 테스트가 검증 수단이다**(5.1절).

조건이 완전히 같지도 않다 — 7.4절에 적는다.

### 7.2 좌석맵·등급 조회 쿼리 (EXPLAIN ANALYZE, 3회 중앙값)

| 쿼리 | 인덱스 없음 | 커버링 인덱스 | 판단 |
|---|---|---|---|
| 좌석맵 (2,000석) | **2.423 ms** / 버퍼 2,130 | **2.469 ms** / 버퍼 354 | 기각 |
| 등급 AVAILABLE COUNT | **0.579 ms** / 버퍼 1,969 | **0.491 ms** / 버퍼 193 | 기각 |
| cold 1회 (재시작 후) | 4.757 ms / **read 196** | 4.015 ms / **read 198** | 기각 |

인덱스 크기 **338MB**(heap 599MB의 56%), 생성 **3.07초**(ACCESS EXCLUSIVE 락).

### 7.3 HOT 업데이트 비율 — 600만 행에서도 100%

`pg_stat_reset()` 후 booking-spike 1회(선점·확정·취소 약 15,500건 UPDATE)마다 측정.

| 테이블 | run 1 | run 2 | run 3 |
|---|---|---|---|
| `schedule_seats` | 15,612 / 15,612 = **100.0%** | 15,659 / 15,659 = **100.0%** | 15,388 / 15,388 = **100.0%** |
| `reservations` | 3,109 / 0 = **0.0%** | 3,110 / 0 = **0.0%** | 3,109 / 0 = **0.0%** |

**Task 022가 남긴 "HOT 측정 표본이 6건이다"가 닫혔다.** 표본이 6건에서 15,000건으로, 데이터가
26,894행에서 600만 행으로 늘어도 `fillfactor 90` + "인덱스를 걸지 않는다"가 HOT을 100% 지킨다.

`reservations`가 0%인 것도 예측대로다 — `idx_reservations_pending_expires_at`가 갱신 대상인
`status`를 조건에 걸고 있어서 UPDATE마다 인덱스 엔트리를 다시 써야 한다.

`reservation_id` 인덱스를 걸면 이 100%가 34%로 떨어진다 (4.9절).

### 7.5 `reservation_id` 인덱스 A/C군 요약

| | 예약 100건 물리 삭제 | HOT 비율 | 예매 p95 | 인덱스 |
|---|---|---|---|---|
| A군 (현행) | **26,906 ms** | **100.0%** | **7.77 ms** | — |
| C군 (인덱스) | **2.71 ms** | **34.1~35.2%** | **8.50 ms** | 40MB, 생성 1.09초 |

### 7.6 그 외 쿼리 (EXPLAIN ANALYZE)

| 쿼리 | 플랜 | 실행 시간 |
|---|---|---|
| 공연 목록 content, page 0 | 인덱스 + Incremental Sort + Memoize | **0.551 ms** |
| 공연 목록 content, page 49 | Seq Scan + full Sort | **0.556 ms** |
| 공연 목록 count | Seq Scan 집계 | **0.114 ms** |
| 공연 목록 + 키워드 (최악, 1,000행 매칭) | Seq Scan + 1,000회 venue 조회 | **1.329 ms** |
| 내 예약 목록 (684건 중 20건) | `idx_reservations_member_created_at` + Memoize 3단 | **1.548 ms** |
| 만료 배치 집계 (`schedules` 3,020행 GROUP BY) | HashAggregate + Seq Scan | **0.743 ms** |
| 로그인 회원 조회 (현재 코드) | **Seq Scan** 503행 | **0.088 ms** |

**병리적인 플랜이 하나도 없다.** 전부 0.1~1.6ms이고, 가장 비싼 좌석맵도 2.4ms다.

## 8. 균일한 데이터가 플랜 해석을 어디까지 비트나

006 9절이 "Task 028의 `EXPLAIN ANALYZE`에서 이 균일성이 결과를 왜곡할 수 있다"고 경고했다.
실제로 **쿼리마다 민감도가 달랐다.**

**비틀린 쪽 — 공연 목록.** `performances` 1,008행 중 1,003행이 `ON_SALE`이라
`idx_performances_status_start_date`의 선두 컬럼 선택도가 99.5%다. 인덱스로 걸러지는 게 5행뿐이다.
더 나쁜 건 두 번째 컬럼인데, `[LOAD]` 공연 1,000개의 `start_date`가 **전부 2026-11-13 하나**다.
그래서 page 0의 플랜에 이런 줄이 나온다.

```
Incremental Sort
  Presorted Key: p1_0.start_date
  Pre-sorted Groups: 4   Full-sort Groups: 1
```

ON_SALE 1,003행 안에 서로 다른 `start_date`가 5종뿐이라서 Incremental Sort가 거의 공짜로 끝난다.
운영 카탈로그라면 날짜가 흩어져 있어 그룹이 수백 개가 되고, 같은 노드가 사실상 완전 정렬이 된다.
**"page 0은 인덱스를 탄다"는 관찰을 운영으로 외삽할 수 없다**는 뜻이다.

**덜 민감한 쪽 — 좌석맵·등급 COUNT.** 이쪽은 `schedule_id = ?` 등가 조건이고, 한 회차의 좌석 수가
2,000이라는 구조는 운영에서도 같다(공연장 규모가 정하는 값이다). 선택도가 데이터 분포가 아니라
스키마에서 나오므로 4.5·4.6절의 결론은 균일성에 흔들리지 않는다.

그래서 이 Task의 결론 중 **좌석맵·HOT 쪽은 그대로 쓸 수 있고, 목록 쪽은 "지금 규모에서는 근거가
없다"까지만 유효하다.**

### 7.4 before/after의 조건이 완전히 같지 않다

006 측정 이후 같은 볼륨을 계속 쓰면서 상태가 달라졌고, 되돌리지 않았다.

| 항목 | 006 측정 시점 | 이번 측정 시점 |
|---|---|---|
| `reservations` 행 수 | 13,798 | 약 43,000 (측정마다 `CANCELLED`로 쌓인다) |
| `schedule_seats` VACUUM | 주입 직후 | 커버링 인덱스 실험 중 `VACUUM (ANALYZE)` 2회 |
| postgres 재시작 | 없음 | cold 측정을 위해 2회 |
| 인덱스 생성/삭제 | 없음 | 338MB 인덱스 생성·삭제 1회 |

그래서 7.1의 +0.12~0.34ms를 "이번 변경 때문"으로 읽으면 안 된다. 예약 행이 3배로 늘면
`idx_reservations_member_created_at`과 부분 인덱스가 커지고, 선점·취소 경로가 그 인덱스를 쓴다.
**이 표는 "개선 폭"이 아니라 "회귀가 없다"까지만 말한다.**

## 9. 한계

- **선점의 쿼리 1문장 감소를 p95로 확인하지 못했다**(7.1). 검증은 쿼리 카운트 테스트로만 돼 있다.
  부하 테스트로 보려면 로그인을 iteration 밖으로 빼고 선점만 때리는 시나리오가 따로 필요하다.
- **before/after 조건이 어긋나 있다**(7.4). 엄밀히 비교하려면 006과 같은 볼륨을 처음부터 다시
  만들고(주입 70초), 같은 데이터 위에서 old/new 이미지를 번갈아 재야 한다. 하지 않았다.
- **이 측정은 전부 "데이터가 메모리에 있는" 구간이다.** DB 1,039MB에 `shared_buffers`는 128MB뿐인데,
  한 회차의 작업 집합이 42페이지(330KB)라 반복 측정에서 항상 warm이다. k6 좌석맵 시나리오도 회차
  60개를 돌리므로 약 20MB로 128MB 안에 들어간다. **회차 수가 늘어 작업 집합이 `shared_buffers`를
  넘기면 4.6절의 결론이 바뀔 수 있다** — 그때는 커버링 인덱스가 읽을 페이지를 실제로 줄인다.
- **데이터가 균일하다.** 1,000개 공연이 전부 같은 공연장·`ON_SALE`이고 **`start_date`까지 같다**.
  `idx_performances_status_start_date`의 선두 컬럼 선택도가 1에 가깝고 두 번째 컬럼은 전부 동일값이라,
  4.7절의 Incremental Sort는 운영에서 다르게 나올 수 있다 (006 9절이 경고한 그대로다). 8절에서
  쿼리별 민감도를 갈라 적었다.
- **`EXPLAIN ANALYZE`는 접속 하나에서 직렬로 쟀다.** 동시에 82 req/s가 들어오는 중의 플랜이 아니다.
  플래너 선택은 같겠지만 버퍼 경합과 락 대기는 이 측정에 없다.
- **쿼리 카운트는 Testcontainers 소량 데이터에서 셌다.** 문장 수는 데이터 크기와 무관하므로 값은
  유효하지만, 운영에서 flush 타이밍이 달라지면 숫자가 바뀔 수 있다.
- **`shared_buffers`가 기본값 128MB다.** DB가 1,039MB인데 손대지 않았다. 4.5절의 결론("인덱스가
  줄이는 게 I/O가 아니다")은 작업 집합이 이 128MB 안에 들어온다는 전제에 기대고 있다. **이 값을
  올리는 것이 인덱스보다 효과가 클 수 있는데 측정하지 않았다.**
- **커버링 인덱스를 `CONCURRENTLY` 없이 걸었다.** 3.07초 동안 `schedule_seats`에 ACCESS EXCLUSIVE
  락이 잡혔다. 실험이라 상관없었지만, V7을 실제로 쓸 일이 생기면 V3_2 주석이 남긴
  `CREATE INDEX CONCURRENTLY` + `-- flyway:executeInTransaction=false`를 써야 한다.

## 10. 다음 단계

- **[Task 029] 캐싱**: 4.5·4.6절이 보여준 건 **이 규모에서 DB가 병목이 아니라는 것**이다. 공연
  목록이 0.55ms인데 캐시를 붙여도 p95에서 보일 여지가 작다. 006이 이미 "적중률을 따로 재야 p95
  개선이 캐시 덕인지 확인된다"고 적었고, 거기에 하나 더 붙는다 — **캐시의 이득은 DB 시간이 아니라
  좌석맵 237.9KB의 직렬화 비용에서 나올 가능성이 크다.** 무엇이 줄어드는지 먼저 정하고 재야 한다.
- **[Task 030] FE 렌더링**: 좌석맵 응답이 여전히 **237.9KB / 2,000석**이다. 이 Task에서 줄이지
  않았다(응답 계약 변경은 범위 밖으로 뒀다).
- **[Task 033] 오픈런 부하 검증**: 4.5절의 결론이 "작업 집합이 `shared_buffers` 128MB 안에 들어온다"는
  전제에 기대고 있다. 포화 구간에서는 그 전제가 깨질 수 있으므로, breakpoint 테스트에서 커버링
  인덱스 후보를 **다시 재야 한다**. 기각은 "지금 규모에서"라는 조건이 붙은 기각이다.
- **[미배정] `shared_buffers`를 올려보지 않았다**(9절). DB가 1,039MB인데 기본값 128MB다. 인덱스를
  추가하는 것보다 이 값을 키우는 쪽이 효과가 클 수 있고, 그건 스키마를 안 건드리는 변경이다.
- **[미배정] `uk_members_email`이 쓰이지 않는다**(4.10절). 지금은 Seq Scan과의 차이가 0.039ms라
  근거가 없지만, 회원 수가 늘면 선형으로 벌어진다. 고치는 방법은 둘이고 성격이 다르다 —
  리포지토리를 `lower(email) = ?`로 바꾸면(인덱스 추가 0) 인증 쿼리의 의미가 바뀌고,
  `members(email)` 인덱스를 새로 만들면 함수 인덱스와 중복된다.
- ~~**[미배정] 쿼리 카운트를 CI에 걸지 않았다.**~~ — **완료**(Task 042_1). 여기 적은 두 줄 중
  **뒷줄만 맞았다.** 테스트는 `./gradlew test`에 섞여 있었고 그 `test`가 CI에서 돌고 있었으므로
  "CI에 걸지 않았다"는 틀렸다 — 로드맵이 이 항목을 그렇게 요약해서 Task 042_1의 근거가
  한동안 어긋나 있었다. 실제 공백은 **"PR에서 눈에 띄게 만드는 장치가 없다"** 하나였고,
  `@Tag("query-count")` + 전용 잡 + `build/query-counts.md`를 Step Summary에 붙이는 것으로 닫았다.

**범위 밖에서 발견한 것**:

- **저장소의 k6 재현 커맨드가 Git Bash에서 안 된다**(6절). `MSYS_NO_PATHCONV=1`이 필요하다.
  006의 재현 방법에는 그 언급이 없어서, 같은 문서를 보고 따라 하면 첫 줄에서 막힌다.
- **`ReservationServiceTest`가 리포지토리 메서드 이름에 묶여 있었다.** 선점 조회를 `findById`에서
  `findByIdWithSchedule`로 바꾸자 목 스텁 13개가 한꺼번에 깨졌다. 유닛 테스트가 "무엇을 하는가"가
  아니라 "어떤 메서드를 부르는가"를 고정하고 있다는 뜻이고, fetch 전략을 바꿀 때마다 같이 깨진다.
  이번에는 스텁만 바꿨다.

---

## 재현 방법

```bash
# 0. Git Bash라면 경로 번역을 꺼야 한다 (6절). PowerShell이면 생략.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# 1. 스택 기동 + 데이터 주입 (006 "재현 방법" 1~3번과 동일, schedule_seats 약 600만 행)

# 2. 쿼리 카운트 테스트 — 이 Task의 유일한 자동 검증 수단이다
#    Task 042_1이 전용 태스크로 분리했다(태그 query-count). `./gradlew test`에는 더 이상 안 섞인다.
cd tikkit-back && ./gradlew queryCountTest
# -> 통과. POST /reservations 가 6문장으로 고정돼 있다
#    build/query-counts.md 에 경로별 문장 수 표가 생긴다 (CI는 이걸 Step Summary에 붙인다)

# 3. Hibernate가 실제로 만든 SQL 받아오기 (SQL을 손으로 재구성하지 않는다 — 2절)
#    application-test.yml 의 show-sql: true 가 켜져 있으므로 리포지토리 테스트 로그에 그대로 찍힌다

# 4. EXPLAIN (ANALYZE, BUFFERS) — 회차를 바꿔 3회씩
docker exec -i tikkit-postgres-prod psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
select ss.id, s.section, s.row_label, s.seat_number, s.pos_x, s.pos_y, ss.status, ss.ticket_grade_id
from schedule_seats ss join seats s on s.id = ss.seat_id
where ss.schedule_id = 21 order by s.pos_y, s.pos_x;"
# -> Sort(0.05ms) + Hash Join + Index Scan(buffers 2130). 약 2.4ms

# 5. 커버링 인덱스 A/B (4.5절). 반드시 VACUUM 후 같은 조건으로 잰다
#    CREATE INDEX idx_ss_cover ON schedule_seats (schedule_id)
#        INCLUDE (seat_id, status, ticket_grade_id);
#    VACUUM (ANALYZE, PARALLEL 0) schedule_seats;   -- PARALLEL 0: /dev/shm 부족으로 죽는다
# -> 버퍼 2130 -> 354 인데 시간은 그대로. DROP 하고 끝낸다

# 6. cold 비교 — shared_buffers를 비우고 한 번도 안 건드린 회차로 (4.6절)
docker restart tikkit-postgres-prod && sleep 8
# -> read=196 vs 198. 실제 페이지 읽기는 같다

# 7. HOT 비율 (7.3절) — booking-spike 1회당 측정
docker exec -i tikkit-postgres-prod psql -c "select pg_stat_reset();"
#    (booking-spike 실행)
docker exec -i tikkit-postgres-prod psql -c "
select relname, n_tup_upd, n_tup_hot_upd,
       round(100.0*n_tup_hot_upd/nullif(n_tup_upd,0), 1) hot_pct
from pg_stat_user_tables where relname in ('schedule_seats','reservations');"
# -> schedule_seats 100.0% / reservations 0.0%

# 8. after 측정 (006 7.1과 같은 조건: 3종 x 3회, --no-deps, 사이에 98-reset)
# -> 탐색 2.48ms / 좌석맵 6.77ms / 예매 7.77ms (중앙값), real_errors 0.00%
```
